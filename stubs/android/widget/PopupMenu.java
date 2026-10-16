package android.widget;
import android.content.Context;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
public class PopupMenu {
    public PopupMenu(Context context, View anchor) {}
    public Menu getMenu() { return null; }
    public interface OnMenuItemClickListener { boolean onMenuItemClick(MenuItem item); }
    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {}
    public void show() {}
}
