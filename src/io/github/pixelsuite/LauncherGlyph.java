package io.github.pixelsuite;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
final class LauncherGlyph extends Drawable {
    private final Paint paint = new Paint(3); private final int kind;
    LauncherGlyph(Context c, int kind) {
        this.kind=kind; TypedValue v=new TypedValue(); c.getTheme().resolveAttribute(android.R.attr.textColorPrimary,v,true);
        try { paint.setColor(c.getResources().getColorStateList(v.resourceId).getDefaultColor()); }
        catch(Exception e) { paint.setColor(0xff888888); }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
    public void draw(Canvas c) {
        c.save(); c.translate(getBounds().left,getBounds().top); c.scale(getBounds().width()/24f,getBounds().height()/24f);
        if(kind>=53&&kind<=55){int n=kind-50;for(int i=0;i<=n;i++){float p=4+16*i/(float)n;c.drawLine(p,4,p,20,paint);c.drawLine(4,p,20,p,paint);}}
        else if(kind==7){c.drawLine(12,4,12,20,paint);c.drawLine(4,12,20,12,paint);}
        else if(kind==8){c.drawLine(4,6,20,6,paint);c.drawLine(9,3,15,3,paint);c.drawLine(6,6,7,21,paint);c.drawLine(7,21,17,21,paint);c.drawLine(17,21,18,6,paint);c.drawLine(10,10,10,17,paint);c.drawLine(14,10,14,17,paint);}
        else if(kind==0) { c.drawRoundRect(3,7,21,20,2,2,paint); c.drawLine(3,7,3,4,paint); c.drawLine(3,4,10,4,paint); c.drawLine(10,4,13,7,paint); c.drawLine(12,11,12,17,paint); c.drawLine(9,14,15,14,paint); }
        else if(kind==3){c.drawLine(3,10,12,3,paint);c.drawLine(12,3,21,10,paint);c.drawLine(6,9,6,21,paint);c.drawLine(6,21,18,21,paint);c.drawLine(18,21,18,9,paint);c.drawLine(12,12,12,18,paint);c.drawLine(9,15,15,15,paint);}
        else if(kind==4){c.drawArc(5,5,19,19,-135,300,false,paint);c.drawLine(7,7,7,3,paint);c.drawLine(7,7,11,7,paint);}
        else if(kind==2) { c.drawRoundRect(3,3,21,21,3,3,paint);c.drawCircle(8,8,1.5f,paint);c.drawLine(4,18,11,11,paint);c.drawLine(11,11,15,15,paint);c.drawLine(15,15,18,12,paint);c.drawLine(18,12,21,15,paint); }
        else if(kind==10){
            c.drawLine(3,6,5.6f,6,paint);c.drawLine(10.4f,6,21,6,paint);c.drawCircle(8,6,2.4f,paint);
            c.drawLine(3,12,13.6f,12,paint);c.drawLine(18.4f,12,21,12,paint);c.drawCircle(16,12,2.4f,paint);
            c.drawLine(3,18,7.6f,18,paint);c.drawLine(12.4f,18,21,18,paint);c.drawCircle(10,18,2.4f,paint);}
        else if(kind==11){c.drawCircle(10.5f,10.5f,6.5f,paint);c.drawLine(15.3f,15.3f,20.5f,20.5f,paint);}
        else if(kind==12){c.drawLine(6,6,18,18,paint);c.drawLine(18,6,6,18,paint);}
        else if(kind==9){c.drawLine(4,6,13,6,paint);c.drawLine(4,12,11,12,paint);c.drawLine(4,18,9,18,paint);c.drawLine(18,4,18,20,paint);c.drawLine(15,17,18,20,paint);c.drawLine(18,20,21,17,paint);}
        else { c.drawLine(5,18,17,6,paint); c.drawLine(17,6,20,9,paint); c.drawLine(20,9,8,21,paint); c.drawLine(8,21,4,22,paint); c.drawLine(4,22,5,18,paint); c.drawLine(4,4,13,4,paint); c.drawLine(8,4,8,12,paint); }
        c.restore();
    }
    public void setTintList(android.content.res.ColorStateList tint){if(tint!=null){paint.setColor(tint.getColorForState(getState(),tint.getDefaultColor()));invalidateSelf();}}
    public void setAlpha(int a){paint.setAlpha(a); invalidateSelf();} public void setColorFilter(ColorFilter f){paint.setColorFilter(f);invalidateSelf();} public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
