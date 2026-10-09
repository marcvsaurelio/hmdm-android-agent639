/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.pro;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import com.hmdm.launcher.AdminReceiver;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.ServerConfig;

import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Headwind MDM Open Source - custom kiosk implementation.
 *
 * Customizações:
 * - Status bar via DevicePolicyManager
 * - Android Lock Task / COSU
 * - Home / Recentes / Notificações
 * - Informações da barra de status
 * - Keyguard
 * - Menu global do botão Power
 * - Tela sempre ligada
 * - Botão de saída do kiosk com 4 cliques
 * - Allowlist automática de todos os aplicativos da configuração
 */
public class ProUtils {

    private static final String TAG = Const.LOG_TAG;

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static DevicePolicyManager getDpm(Context context) {
        return (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private static ComponentName getAdmin(Context context) {
        return new ComponentName(context, AdminReceiver.class);
    }

    private static ServerConfig getConfig(Context context) {
        SettingsHelper settingsHelper =
                SettingsHelper.getInstance(context.getApplicationContext());

        return settingsHelper != null ? settingsHelper.getConfig() : null;
    }

    private static boolean enabled(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    /**
     * Retorna todos os pacotes que devem poder participar do Lock Task:
     * - Headwind MDM
     * - Aplicativo principal / kiosk
     * - Todos os aplicativos configurados e não marcados para remoção
     * - Settings somente quando solicitado temporariamente
     */
    private static Set<String> buildLockTaskPackages(
            Context context,
            ServerConfig config,
            String kioskApp,
            boolean enableSettings) {

        Set<String> packages = new LinkedHashSet<>();

        // O próprio agente/launcher nunca pode ficar fora da allowlist.
        packages.add(context.getPackageName());

        // Aplicativo principal / Kiosk.
        if (kioskApp != null && !kioskApp.trim().isEmpty()) {
            packages.add(kioskApp.trim());
        }

        // Todos os aplicativos configurados no Headwind.
        if (config != null && config.getApplications() != null) {
            List<Application> applications = config.getApplications();

            for (Application application : applications) {
                if (application == null || application.isRemove()) {
                    continue;
                }

                String pkg = application.getPkg();

                if (pkg != null && !pkg.trim().isEmpty()) {
                    packages.add(pkg.trim());
                }
            }
        }

        // Settings pode ser liberado temporariamente pelo fluxo já existente.
        if (enableSettings) {
            packages.add(Const.SETTINGS_PACKAGE_NAME);
        }

        return packages;
    }

    /**
     * Monta os recursos que permanecem HABILITADOS durante Lock Task.
     * Tudo que não estiver nos flags permanece bloqueado pelo Android.
     */
    private static int buildLockTaskFeatures(ServerConfig config) {
        int flags = DevicePolicyManager.LOCK_TASK_FEATURE_NONE;

        boolean home = enabled(config.getKioskHome());
        boolean recents = enabled(config.getKioskRecents());
        boolean notifications = enabled(config.getKioskNotifications());

        /*
         * Android exige HOME quando OVERVIEW ou NOTIFICATIONS são habilitados.
         * Se o administrador habilitar Recentes ou Notificações, HOME é
         * adicionado automaticamente para evitar IllegalArgumentException.
         */
        if (recents || notifications) {
            if (!home) {
                Log.w(TAG,
                        "HOME habilitado automaticamente porque Recentes/Notificações exigem HOME no LockTask");
            }
            home = true;
        }

        if (home) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
        }

        if (recents) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        }

        if (notifications) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS;
        }

        if (enabled(config.getKioskSystemInfo())) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO;
        }

        if (enabled(config.getKioskKeyguard())) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD;
        }

        /*
         * kioskLockButtons=true significa bloquear o menu exibido ao manter
         * Power pressionado. Portanto GLOBAL_ACTIONS só é permitido quando
         * kioskLockButtons=false.
         */
        if (!enabled(config.getKioskLockButtons())) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
        }

        return flags;
    }

    // ------------------------------------------------------------------------
    // Compatibilidade com chamadas da edição original
    // ------------------------------------------------------------------------

    public static boolean isPro() {
        return false;
    }

    public static boolean kioskModeRequired(Context context) {
        ServerConfig config = getConfig(context);
        return config != null && config.isKioskMode();
    }

    public static void initCrashlytics(Context context) {
        // Mantido como stub na edição custom.
    }

    public static void sendExceptionToCrashlytics(Throwable e) {
        // Mantido como stub na edição custom.
    }

    public static boolean checkAccessibilityService(Context context) {
        return true;
    }

    public static boolean checkUsageStatistics(Context context) {
        return true;
    }

    // ------------------------------------------------------------------------
    // Status bar
    // ------------------------------------------------------------------------

    /**
     * Compatibilidade com blockStatusBar fora do Lock Task e fallback antes
     * do Lock Task iniciar.
     */
    public static View preventStatusBarExpansion(Activity activity) {
        try {
            DevicePolicyManager dpm = getDpm(activity);
            ComponentName admin = getAdmin(activity);

            if (dpm != null &&
                    dpm.isDeviceOwnerApp(activity.getPackageName())) {

                dpm.setStatusBarDisabled(admin, true);
                Log.i(TAG, "Status bar disabled by DevicePolicyManager");
            } else {
                Log.w(TAG,
                        "Status bar not disabled: application is not Device Owner");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable status bar", e);
        }

        return null;
    }

    public static void allowStatusBarExpansion(Activity activity) {
        try {
            DevicePolicyManager dpm = getDpm(activity);
            ComponentName admin = getAdmin(activity);

            if (dpm != null &&
                    dpm.isDeviceOwnerApp(activity.getPackageName())) {

                dpm.setStatusBarDisabled(admin, false);
                Log.i(TAG, "Status bar enabled by DevicePolicyManager");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to enable status bar", e);
        }
    }

    /**
     * No Android moderno o Lock Task controla Overview/SystemUI.
     * Mantido por compatibilidade com MainActivity.
     */
    public static View preventApplicationsList(Activity activity) {
        return null;
    }

    // ------------------------------------------------------------------------
    // Botão de saída do Kiosk
    // ------------------------------------------------------------------------

    /**
     * O próprio MainActivity decide quando esse botão deve existir.
     * Aqui exigimos quatro cliques, conforme a opção exibida pelo servidor.
     */
    public static View createKioskUnlockButton(Activity activity) {
        Button button = new Button(activity);
        button.setText("Sair do quiosque");

        final int[] clickCount = {0};

        button.setOnClickListener(v -> {
            clickCount[0]++;

            Log.i(TAG,
                    "Kiosk exit click " + clickCount[0] +
                            "/" + Const.KIOSK_UNLOCK_CLICK_COUNT);

            if (clickCount[0] >= Const.KIOSK_UNLOCK_CLICK_COUNT) {
                clickCount[0] = 0;
                unlockKiosk(activity);
            }
        });

        return button;
    }

    // ------------------------------------------------------------------------
    // Estado / aplicativo Kiosk
    // ------------------------------------------------------------------------

    public static boolean isKioskAppInstalled(Context context) {
        ServerConfig config = getConfig(context);

        if (config == null) {
            return false;
        }

        String kioskApp = config.getMainApp();

        if (kioskApp == null ||
                kioskApp.trim().isEmpty() ||
                kioskApp.equals(context.getPackageName())) {

            return true;
        }

        try {
            context.getPackageManager().getPackageInfo(kioskApp, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Kiosk application not installed: " + kioskApp);
            return false;
        }
    }

    public static boolean isKioskModeRunning(Context context) {
        try {
            ActivityManager activityManager =
                    (ActivityManager)
                            context.getSystemService(Context.ACTIVITY_SERVICE);

            return activityManager != null &&
                    activityManager.getLockTaskModeState()
                            != ActivityManager.LOCK_TASK_MODE_NONE;

        } catch (Exception e) {
            Log.e(TAG, "Failed to check LockTask state", e);
            return false;
        }
    }

    public static Intent getKioskAppIntent(
            String kioskApp,
            Activity activity) {

        if (kioskApp == null || kioskApp.trim().isEmpty()) {
            kioskApp = activity.getPackageName();
        }

        Intent intent =
                activity.getPackageManager()
                        .getLaunchIntentForPackage(kioskApp);

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }

        return intent;
    }

    // ------------------------------------------------------------------------
    // Allowlist do Lock Task
    // ------------------------------------------------------------------------

    public static void updateKioskAllowedApps(
            String kioskApp,
            Activity activity,
            boolean enableSettings) {

        try {
            DevicePolicyManager dpm = getDpm(activity);
            ComponentName admin = getAdmin(activity);
            ServerConfig config = getConfig(activity);

            if (dpm == null ||
                    !dpm.isDeviceOwnerApp(activity.getPackageName())) {

                Log.w(TAG,
                        "Cannot configure LockTask packages: not Device Owner");
                return;
            }

            Set<String> packages =
                    buildLockTaskPackages(
                            activity,
                            config,
                            kioskApp,
                            enableSettings
                    );

            dpm.setLockTaskPackages(
                    admin,
                    packages.toArray(new String[0])
            );

            Log.i(TAG, "LockTask packages allowed: " + packages);

        } catch (Exception e) {
            Log.e(TAG, "Failed to configure LockTask packages", e);
        }
    }

    // ------------------------------------------------------------------------
    // Opções do Kiosk
    // ------------------------------------------------------------------------

    public static void updateKioskOptions(Activity activity) {
        try {
            ServerConfig config = getConfig(activity);

            if (config == null) {
                return;
            }

            DevicePolicyManager dpm = getDpm(activity);
            ComponentName admin = getAdmin(activity);

            if (dpm == null ||
                    !dpm.isDeviceOwnerApp(activity.getPackageName())) {

                Log.w(TAG, "Cannot update kiosk options: not Device Owner");
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                int flags = buildLockTaskFeatures(config);

                dpm.setLockTaskFeatures(admin, flags);

                Log.i(TAG, "LockTask features applied: " + flags);
            }

            // Ecrã/tela sempre ligada enquanto a Activity estiver visível.
            if (enabled(config.getKioskScreenOn())) {
                activity.getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                );
                Log.i(TAG, "Keep screen on enabled");
            } else {
                activity.getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                );
                Log.i(TAG, "Keep screen on disabled");
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to update kiosk options", e);
        }
    }

    // ------------------------------------------------------------------------
    // Início do COSU / Lock Task
    // ------------------------------------------------------------------------

    public static boolean startCosuKioskMode(
            String kioskApp,
            Activity activity,
            boolean enableSettings) {

        try {
            DevicePolicyManager dpm = getDpm(activity);

            if (dpm == null ||
                    !dpm.isDeviceOwnerApp(activity.getPackageName())) {

                Log.w(TAG,
                        "Cannot start kiosk: application is not Device Owner");
                return false;
            }

            if (kioskApp == null || kioskApp.trim().isEmpty()) {
                kioskApp = activity.getPackageName();
            }

            // 1) Allowlist completa.
            updateKioskAllowedApps(
                    kioskApp,
                    activity,
                    enableSettings
            );

            // 2) Configura SystemUI/Power/Keyguard/Screen-on.
            updateKioskOptions(activity);

            /*
             * Se o próprio Headwind é o app principal, bloqueia a task atual.
             */
            if (kioskApp.equals(activity.getPackageName())) {
                if (!isKioskModeRunning(activity)) {
                    activity.startLockTask();
                }

                Log.i(TAG, "LockTask started on Headwind launcher");
                return true;
            }

            /*
             * Aplicativo externo como principal do Kiosk.
             */
            Intent intent = getKioskAppIntent(kioskApp, activity);

            if (intent == null) {
                Log.e(TAG,
                        "No launch intent for kiosk application: " + kioskApp);
                return false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLockTaskEnabled(true);

                activity.startActivity(
                        intent,
                        options.toBundle()
                );
            } else {
                /*
                 * Compatibilidade mínima Android 8.x.
                 * O projeto atual tem minSdk 26, mas o alvo principal aqui é
                 * Android 11+.
                 */
                if (!isKioskModeRunning(activity)) {
                    activity.startLockTask();
                }
                activity.startActivity(intent);
            }

            Log.i(TAG, "COSU kiosk started: " + kioskApp);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start COSU kiosk", e);
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // Saída do Kiosk
    // ------------------------------------------------------------------------

    public static void unlockKiosk(Activity activity) {
        /*
         * Primeiro tenta sair do Lock Task pela Activity atual.
         */
        try {
            if (isKioskModeRunning(activity)) {
                activity.stopLockTask();
                Log.i(TAG, "LockTask stopped");
            }
        } catch (Exception e) {
            Log.w(TAG, "stopLockTask failed", e);
        }

        try {
            DevicePolicyManager dpm = getDpm(activity);
            ComponentName admin = getAdmin(activity);

            if (dpm != null &&
                    dpm.isDeviceOwnerApp(activity.getPackageName())) {

                // Remove allowlist. Em Android M+ tarefas lockadas removidas
                // da allowlist também são finalizadas pelo sistema.
                dpm.setLockTaskPackages(
                        admin,
                        new String[]{}
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Fora do Kiosk, volta a permitir o menu global do Power.
                    dpm.setLockTaskFeatures(
                            admin,
                            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                    );
                }

                // Compatibilidade com o bloqueio antigo da barra.
                dpm.setStatusBarDisabled(admin, false);
            }

            activity.getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );

            Log.i(TAG, "Kiosk unlocked");

        } catch (Exception e) {
            Log.e(TAG, "Failed to unlock kiosk", e);
        }
    }

    // ------------------------------------------------------------------------
    // Atualização da configuração vinda do servidor
    // ------------------------------------------------------------------------

    /**
     * Mantém allowlist e flags sincronizados sempre que uma nova configuração
     * chega do servidor.
     *
     * IMPORTANTE:
     * este método usa a MESMA allowlist de updateKioskAllowedApps().
     * Isso evita o defeito do z2 em que processConfig() sobrescrevia a lista
     * apenas com Headwind + mainApp e fazia os demais ícones pararem de abrir.
     */
    public static void processConfig(
            Context context,
            ServerConfig config) {

        if (config == null) {
            return;
        }

        try {
            DevicePolicyManager dpm = getDpm(context);
            ComponentName admin = getAdmin(context);

            if (dpm == null ||
                    !dpm.isDeviceOwnerApp(context.getPackageName())) {

                Log.w(TAG,
                        "Configuration not applied: application is not Device Owner");
                return;
            }

            if (config.isKioskMode()) {
                String kioskApp = config.getMainApp();

                Set<String> packages =
                        buildLockTaskPackages(
                                context,
                                config,
                                kioskApp,
                                false
                        );

                dpm.setLockTaskPackages(
                        admin,
                        packages.toArray(new String[0])
                );

                Log.i(TAG,
                        "Configuration updated - LockTask packages: " + packages);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    int flags = buildLockTaskFeatures(config);

                    dpm.setLockTaskFeatures(
                            admin,
                            flags
                    );

                    Log.i(TAG,
                            "Configuration updated - LockTask features: " + flags);
                }

                /*
                 * Dentro do Lock Task, a SystemUI é controlada pelos
                 * LOCK_TASK_FEATURE_*.
                 *
                 * Não forçamos setStatusBarDisabled() aqui para não conflitar
                 * com "Habilitar notificações" e "Habilitar informações da
                 * barra de status".
                 */

            } else {
                /*
                 * Saiu do modo Kiosk.
                 * Remover os pacotes da allowlist encerra as tarefas lockadas
                 * em Android M+.
                 */
                dpm.setLockTaskPackages(
                        admin,
                        new String[]{}
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.setLockTaskFeatures(
                            admin,
                            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                    );
                }

                /*
                 * Fora do modo Kiosk, preserva a opção legada blockStatusBar.
                 */
                dpm.setStatusBarDisabled(
                        admin,
                        enabled(config.getLockStatusBar())
                );

                Log.i(TAG, "Kiosk configuration disabled");
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to process kiosk configuration", e);
        }
    }

    // ------------------------------------------------------------------------
    // Localização
    // ------------------------------------------------------------------------

    public static void processLocation(
            Context context,
            Location location,
            String provider) {

        // Mantido como stub; o módulo de localização continua separado.
    }

    // ------------------------------------------------------------------------
    // Informações do aplicativo
    // ------------------------------------------------------------------------

    public static String getAppName(Context context) {
        return context.getString(R.string.app_name);
    }

    public static String getCopyright(Context context) {
        return "(c) "
                + Calendar.getInstance().get(Calendar.YEAR)
                + " "
                + context.getString(R.string.vendor);
    }
}
