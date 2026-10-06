package app.rike.offline;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small native line icons; no fonts, raster downloads or external assets. */
final class ZenIcon extends Drawable {
    static final int SUN=0,MOON=1,LOCK=2,MORE=3,SETTINGS=4,EDIT=5,DELETE=6;
    private int kind,color;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ZenIcon(int kind,int color){this.kind=kind;this.color=color;}
    void update(int kind,int color){this.kind=kind;this.color=color;invalidateSelf();}
    @Override public void draw(Canvas canvas){
        int state=canvas.save();Rect b=getBounds();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
        paint.setColor(color);paint.setStrokeWidth(1.6f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
        if(kind==SUN){canvas.drawCircle(12,12,4,paint);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+(float)Math.cos(a)*7,12+(float)Math.sin(a)*7,12+(float)Math.cos(a)*9,12+(float)Math.sin(a)*9,paint);}}
        else if(kind==MOON){Path p=new Path();p.moveTo(17,3);p.cubicTo(7,-1,0,9,6,17);p.cubicTo(10,23,20,22,22,14);p.cubicTo(12,18,7,8,17,3);canvas.drawPath(p,paint);}
        else if(kind==LOCK){canvas.drawRoundRect(5,10,19,21,2,2,paint);canvas.drawArc(8,2,16,16,180,180,false,paint);canvas.drawLine(8,9,8,11,paint);canvas.drawLine(16,9,16,11,paint);canvas.drawLine(12,14,12,17,paint);}
        else if(kind==SETTINGS){canvas.drawCircle(12,12,6,paint);canvas.drawCircle(12,12,2,paint);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+(float)Math.cos(a)*6,12+(float)Math.sin(a)*6,12+(float)Math.cos(a)*9,12+(float)Math.sin(a)*9,paint);}}
        else if(kind==DELETE){canvas.drawLine(4,6,20,6,paint);canvas.drawLine(9,3,15,3,paint);canvas.drawLine(6,6,7,21,paint);canvas.drawLine(18,6,17,21,paint);canvas.drawLine(7,21,17,21,paint);canvas.drawLine(10,10,10,17,paint);canvas.drawLine(14,10,14,17,paint);}
        else if(kind==EDIT){Path p=new Path();p.moveTo(4,20);p.lineTo(5,15);p.lineTo(16,4);p.lineTo(20,8);p.lineTo(9,19);p.close();canvas.drawPath(p,paint);canvas.drawLine(13,7,17,11,paint);}
        else{paint.setStyle(Paint.Style.FILL);for(int y:new int[]{5,12,19})canvas.drawCircle(12,y,1.4f,paint);}
        canvas.restoreToCount(state);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
