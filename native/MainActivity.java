package app.runelift;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;
import ee.forgr.capacitor.social.login.GoogleProvider;
import ee.forgr.capacitor.social.login.ModifiedMainActivityForSocialLoginPlugin;
import ee.forgr.capacitor.social.login.SocialLoginPlugin;

/* Activité principale : renvoie la réponse de Google (autorisation Drive) au module de connexion. */
public class MainActivity extends BridgeActivity implements ModifiedMainActivityForSocialLoginPlugin {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RLHealthPlugin.class);
        registerPlugin(RLWearPlugin.class);
        registerPlugin(RLLivePlugin.class);
        super.onCreate(savedInstanceState);
        RLLivePlugin.takeIntent(this, getIntent()); // ouvert depuis le widget ou une notification
        // Barres du haut et du bas sombres comme l'appli (sinon blanches quand le téléphone est en mode clair)
        android.view.Window w = getWindow(); int bg = 0xFF0D0F12;
        w.getDecorView().setBackgroundColor(bg);
        if (android.os.Build.VERSION.SDK_INT < 35) { w.setStatusBarColor(bg); w.setNavigationBarColor(bg); }
        androidx.core.view.WindowInsetsControllerCompat ic = androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
        ic.setAppearanceLightStatusBars(false); ic.setAppearanceLightNavigationBars(false);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.content.SharedPreferences p = getSharedPreferences("rl_wear", MODE_PRIVATE);
            if (!p.getBoolean("askedNotif", false)) { p.edit().putBoolean("askedNotif", true).apply(); requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        RLLivePlugin.takeIntent(this, intent);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode >= GoogleProvider.REQUEST_AUTHORIZE_GOOGLE_MIN && requestCode < GoogleProvider.REQUEST_AUTHORIZE_GOOGLE_MAX) {
            PluginHandle h = getBridge().getPlugin("SocialLogin");
            if (h != null && h.getInstance() instanceof SocialLoginPlugin) {
                ((SocialLoginPlugin) h.getInstance()).handleGoogleLoginIntent(requestCode, data);
            }
        }
    }

    @Override
    public void IHaveModifiedTheMainActivityForTheUseWithSocialLoginPlugin() {}
}
