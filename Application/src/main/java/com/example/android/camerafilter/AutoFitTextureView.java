package com.example.android.camerafilter;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log; // для записи в лог
import android.view.TextureView;
import java.util.Locale; // для String.format

// класс наследник от TextureView, просмотр потока с определенным соотношением сторон 
public class AutoFitTextureView extends TextureView {

    private int mRatioWidth = 0;
    private int mRatioHeight = 0;

    private static final String TAG = "CameraActivity"; // тег для лога Log

    public AutoFitTextureView(Context context) {
        this(context, null);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

     // устанавливает соотношение сторон для предпросмотра, размеры вычисляются на основании отношения сторон
     // указанные в параметрах величины не имеют значения, важно их пропорции т.е. (width, height) = (2, 3) = (4, 6).
    public void setAspectRatio(int width, int height) {

        Log.d(TAG, "метод setAspectRatio" + String.format(Locale.getDefault(), "(%d, %d)", width, height));
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("setAspectRatio(): размеры не могут быть отрицательными.");
        }
        mRatioWidth = width;
        mRatioHeight = height;
        requestLayout();
    }

    // метод событие для определения измененной ширины и высоты TextureView
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        Log.d(TAG, "метод onMeasure" + String.format(Locale.getDefault(), "(%d, %d)", width, height));

        if (0 == mRatioWidth || 0 == mRatioHeight) {
            setMeasuredDimension(width, height);
        } else {
            if (width > height * mRatioWidth / mRatioHeight) { // для горизонтального положения
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
            } else { // для вертикального положения
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
            }
        }
    }
}
