package io.github.pixelsuite;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/** One allowlisted destination per action, shared by the home and About pages. */
final class CommunityLinks {
    static final String[] NAMES = {"Telegram", "Coffee", "Patreon"};
    static final String[] CAPTIONS = {"Join the community", "Buy me a coffee", "Become a patron"};
    static final String[] ICONS = {"brand_telegram", "brand_coffee", "brand_patreon"};
    static final String[] URLS = {"https://t.me/RovianDev", "https://buymeacoffee.com/roviandev", "https://www.patreon.com/c/RovianDev"};
    static final int[] BADGES = {0xFFEAF7FF, 0xFFFFDD00, 0xFFFFFFFF};
    private CommunityLinks() {}
    static void open(Context context, int index) {
        if (index < 0 || index >= URLS.length) return;
        if(index==0){
            // Let installed Telegram clients show the public community's native Join screen.
            // Existing members open the conversation. This never joins on the user's behalf.
            try{context.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("tg://resolve?domain=RovianDev")));return;}
            catch(ActivityNotFoundException unavailable){}catch(SecurityException denied){}
        }
        try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(URLS[index]))); }
        catch (ActivityNotFoundException unavailable) { Toast.makeText(context,"No app is available to open this link",Toast.LENGTH_SHORT).show(); }
        catch (SecurityException denied) { Toast.makeText(context,"This link could not be opened",Toast.LENGTH_SHORT).show(); }
    }
}
