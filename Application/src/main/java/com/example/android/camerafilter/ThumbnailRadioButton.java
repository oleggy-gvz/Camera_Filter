package com.example.android.camerafilter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.RadioButton;
import android.support.v7.widget.AppCompatRadioButton;

// кнопка Thumbnail расширяет кнопку Radio
// виджет переопределяет отрисовку заднего фона кнопки RadioButton через рисование StateList
// каждое состояние имеет LayerDrawable с изображением миниатюр и прямоугольником фокуса
// используем оригинальный текст из Radio Buttons как метку, потому что LayerDrawable показал некоторые проблемы с Canvas.drawText ()

public class ThumbnailRadioButton extends AppCompatRadioButton {
    public ThumbnailRadioButton(Context context) {
        super(context);
        init();
    }

    public ThumbnailRadioButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ThumbnailRadioButton(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        setButtonDrawable(android.R.color.transparent);
    }

    public void setThumbnail(Bitmap bitmap) {
        // рисование битмапа
        BitmapDrawable bmp = new BitmapDrawable(getResources(), bitmap);
        bmp.setGravity(Gravity.CENTER);

        int strokeWidth = 24;
        // проверенное состояние
        ShapeDrawable rectChecked = new ShapeDrawable(new RectShape());
        rectChecked.getPaint().setColor(0xFFFFFFFF);
        rectChecked.getPaint().setStyle(Paint.Style.STROKE);
        rectChecked.getPaint().setStrokeWidth(strokeWidth);
        rectChecked.setIntrinsicWidth(bitmap.getWidth() + strokeWidth);
        rectChecked.setIntrinsicHeight(bitmap.getHeight() + strokeWidth);
        Drawable drawableArray[] = new Drawable[]{bmp, rectChecked};
        LayerDrawable layerChecked = new LayerDrawable(drawableArray);

        // не проверенное состояние
        ShapeDrawable rectUnchecked = new ShapeDrawable(new RectShape());
        rectUnchecked.getPaint().setColor(0x0);
        rectUnchecked.getPaint().setStyle(Paint.Style.STROKE);
        rectUnchecked.getPaint().setStrokeWidth(strokeWidth);
        rectUnchecked.setIntrinsicWidth(bitmap.getWidth() + strokeWidth);
        rectUnchecked.setIntrinsicHeight(bitmap.getHeight() + strokeWidth);
        Drawable drawableArray2[] = new Drawable[]{bmp, rectUnchecked};
        LayerDrawable layerUnchecked = new LayerDrawable(drawableArray2);

        // рисование StateList
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_checked}, layerChecked);
        states.addState(new int[]{}, layerUnchecked);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            setBackground(states);
        } else {
            // отказ от описания если версия ниже JELLY_BEAN
            setBackgroundDrawable(states);
        }

        // смещение текста описания в центр-низ кнопки
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(getTextSize());
        paint.setTypeface(getTypeface());
        float w = paint.measureText(getText(), 0, getText().length());
        setPadding(getPaddingLeft() + (int) ((bitmap.getWidth() - w) / 2.f + .5f),
                getPaddingTop() + (int) (bitmap.getHeight() * 0.70),
                getPaddingRight(),
                getPaddingBottom());

        setShadowLayer(5, 0, 0, Color.BLACK);
    }
}