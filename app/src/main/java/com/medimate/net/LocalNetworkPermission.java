package com.medimate.net;

import java.net.URI;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.medimate.BuildConfig;

/**
 * 개발 서버가 사설 주소(에뮬레이터 10.0.2.2, PC의 LAN IP)일 때 필요한 로컬 네트워크 권한을 요청한다.
 * 권한은 debug 매니페스트에만 선언되어 있으므로 릴리스 빌드에서는 아무 일도 하지 않는다.
 */
public final class LocalNetworkPermission {

    public static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    /** Android 17. 이 버전부터 사설 주소 접속에 권한이 필요하다. */
    private static final int REQUIRED_SINCE_SDK = 37;

    private LocalNetworkPermission() {
    }

    /** Activity의 onCreate에서 호출한다 (registerForActivityResult는 onStart 전에만 가능). */
    public static void requestIfNeeded(ComponentActivity activity) {
        if (Build.VERSION.SDK_INT < REQUIRED_SINCE_SDK || !isPrivateHost(BuildConfig.API_BASE_URL)
                || !isDeclared(activity)
                || ContextCompat.checkSelfPermission(activity, PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityResultLauncher<String> launcher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> { });
        launcher.launch(PERMISSION);
    }

    /** IP 리터럴과 localhost만 판정한다 (DNS 조회는 메인 스레드에서 할 수 없다). */
    static boolean isPrivateHost(String baseUrl) {
        String host;
        try {
            host = URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        if (host.equals("localhost") || host.endsWith(".local") || host.startsWith("10.")
                || host.startsWith("192.168.") || host.startsWith("127.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            try {
                int second = Integer.parseInt(parts[1]);
                return second >= 16 && second <= 31;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isDeclared(ComponentActivity activity) {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) {
                return false;
            }
            for (String p : info.requestedPermissions) {
                if (PERMISSION.equals(p)) {
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // 자기 자신은 항상 있다
        }
        return false;
    }
}
