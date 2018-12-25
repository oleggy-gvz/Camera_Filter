package com.example.android.camerafilter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.support.v8.renderscript.Allocation;
import android.support.v8.renderscript.Element;
import android.support.v8.renderscript.RenderScript;
import android.support.v8.renderscript.ScriptIntrinsicBlur;
import android.support.v8.renderscript.ScriptIntrinsicConvolve5x5;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.content.Intent; // вызов второй активности
import android.util.Log; // для записи в лог

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class FilterActivity extends AppCompatActivity {

    private Bitmap mBitmapIn; // битмап для хранения входного изображения для обработки
    private Bitmap mBitmapOut; // массив битмапов для хранения выходного фильтрованного изображения
    private ImageView mImageView; // фоновый рисунок ImageView

    private RenderScript mRS; // создаем объект фреймворка для расчетов
    private Allocation mInAllocation; // создаем объект для передачи данных в фреймфорк
    private Allocation mOutAllocation; // создаем объект для получения данных из фреймфорка

    private ScriptIntrinsicBlur mScriptBlur; // создаем объект фильтра гаусса
    private ScriptIntrinsicConvolve5x5 mScriptConvolve; // создаем объект фильтра свертки

    private final int MODE_BLUR = 0; // режим фильтрации по гауссу
    private final int MODE_CONVOLVE = 1; // режим фильтрации резкости
    private final int JPEG_QUALITY = 95; // качество сжатия JPEG файла, в %

    private HandlerThread mBackgroundThread; // дополнительный поток для запуска задач, которые не должны блокировать UI
    private Handler mBackgroundHandler; // поток для запуска задач в фоновом режиме

    private int mFilterMode = MODE_BLUR; // по умолчанию выбран режим фильтрации по гауссу
    private String foto_path; // путь к исходному файлу сохраненному в активности камеры

    private RenderScriptTask mLatestTask = null; // создаем асинхронную задачу для RenderScript
    private static final String TAG = "FilterActivity"; // тег для лога Log

    // метод-событие создания активности
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //Log.d(TAG, "событие onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter);

        // установка обрабатываемого изображения, загружаем битмап из ресурса
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        // получаем путь к сохраненному файлу JPEG сделаному в активности камеры
        foto_path = getIntent().getStringExtra("foto_path");
        mBitmapIn = BitmapFactory.decodeFile(foto_path); // устанавливаем входное изображение на этот снимок

        // создаем пустой битмап той же ширины/высоты что и входной битмап
        mBitmapOut = Bitmap.createBitmap(mBitmapIn.getWidth(), mBitmapIn.getHeight(),
                mBitmapIn.getConfig()); // конфигурацию формата (Bitmap.Config) указываем такую же как и входного битмапа

        mImageView = (ImageView) findViewById(R.id.imageView); // присваиваем фоновому элементу ImageView изображение из R.id.imageView
        mImageView.setImageBitmap(mBitmapOut); // устанавливаем текущий битмап как содержимое этого ImageView

        // связь view элемента кнопки с виджетом кнопки Save
        final Button button = (Button) findViewById(R.id.button);

        button.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {

                File mFileFilter = new File(getBaseName(foto_path) + "_filtered.jpg");
                mBackgroundHandler.post(new BitmapSaver(mBitmapOut, mFileFilter));

                Intent intent = new Intent(FilterActivity.this, CameraActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            }
        });

        // связь view элемента ползунка с виджетом ползунка
        final SeekBar seekbar = (SeekBar) findViewById(R.id.seekBar);
        seekbar.setProgress(50); // устанавливаем текущее значение ползунка

        // создание обработчика для отслеживания изменений ползунка
        seekbar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            // метод-событие, если ползунок был передвинут
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                //Log.d(TAG, "событие onProgressChanged(...)");
                updateImage(progress); // обновляем изображение в зависимости от положения ползунка
            }

            // метод-событие, если на ползунок было нажатие для перемещения
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            // метод-событие, когда ползунок был отпущен после перемещения
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // установка выбранного эффекта при нажатии на одну из кнопок
        // связь viewэлемента кнопки 1 с виджетом кнопки 1
        RadioButton radio0 = (RadioButton) findViewById(R.id.radio0);
        // создание обработчика для отслеживания изменений кнопки 1
        radio0.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            // метод-событие когда кнопка была изменена
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    mFilterMode = MODE_BLUR; // устанавливаем режим фильтрации размытия
                    updateImage(seekbar.getProgress()); // фильтруем изображение по текущему значению ползунка
                }
            }
        });

        // создание обработчика для отслеживания изменений кнопки 2
        RadioButton radio1 = (RadioButton) findViewById(R.id.radio1);
        // создание обработчика для отслеживания изменений кнопки 2
        radio1.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            // метод-событие когда кнопка была изменена
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    mFilterMode = MODE_CONVOLVE; // устанавливаем режим фильтрации резкости
                    updateImage(seekbar.getProgress()); // фильтруем изображение по текущему значению ползунка
                }
            }
        });

        createScript(); // создаем renderScript
        createThumbnail(); // создаем эскизы

        // значения по умолчанию при первом запуске активности
        mFilterMode = MODE_BLUR; // устанавливаем режим фильтрации размытия

        // фильтруем изображение по текущему значению ползунка
        updateImage(seekbar.getProgress());
    }

    // событие-метод когда отображаем активность
    @Override
    public void onResume() {
        //Log.d(TAG, "событие onResume()");
        super.onResume();

        startBackgroundThread(); // запускаем фоновые потоки
    }

    // событие-метод когда покидаем активность
    @Override
    public void onPause() {
        //Log.d(TAG, "событие onPause()");
        super.onPause();

        stopBackgroundThread(); // останавливаем фоновые потоки
    }

    // событие при нажатии кнопки назад
    @Override
    public void onBackPressed()
    {
        super.onBackPressed();

        File mFile;
        mFile = new File(foto_path);
        if (!mFile.delete()) {
            Log.e(TAG, "onBackPressed(): не удалось удалить временный файл");
        }
    }

    // метод запускает фоновый поток Handler и его связи
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("FilterBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    // метод остановки фонового потока Handler и его связей
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // метод удаляет в абсолютном пути к файлу его расширение
    public static String getBaseName(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index == -1) {
            return fileName;
        } else {
            return fileName.substring(0, index);
        }
    }

    // метод создает объект renderScript для выполнения расчетов
    private void createScript() {
        //Log.d(TAG, "метод createScript()");
        mRS = RenderScript.create(this); // создаем контекст процесса

        // создаем входной массив Allocation из битмапа входного изображения
        mInAllocation = Allocation.createFromBitmap(mRS, mBitmapIn);

        // создаем выходной массив Allocation из битмапов выходных изображений
        mOutAllocation = Allocation.createFromBitmap(mRS, mBitmapOut);

        // RenderScript имеетметоды-фильтры такие как размытие, свертывание и т.д.
        // Эти методы удобны для конкретных операций без записи ядра RenderScript.
        // задаем фильтры размытия, свертки и цветовой матрицы

        // U8_4 - данные состоят из векторов 4-х беззначных значений типа 8-bit
        // создаем внутреннюю среду для применения
        mScriptBlur = ScriptIntrinsicBlur.create(mRS, Element.U8_4(mRS)); //  размытия к распределению
        mScriptConvolve = ScriptIntrinsicConvolve5x5.create(mRS, Element.U8_4(mRS)); // свертки матрицы 5x5
    }

    // метод применения фильтра определенного режима, заданного mFilterMode
    private void performFilter(Allocation inAllocation, Allocation outAllocation, Bitmap bitmapOut, float value) {
        //Log.d(TAG, "метод performFilter(...)");
        switch (mFilterMode) {
            case MODE_BLUR: // фильтр размытия
                mScriptBlur.setRadius(value); // установка радиуса размытия

                mScriptBlur.setInput(inAllocation); // установка входных данных
                // вызов самого фильтра и запись результатов в выходной Allocation
                mScriptBlur.forEach(outAllocation);
                break;

            case MODE_CONVOLVE: { // фильтр четкости
                @SuppressWarnings("UnnecessaryLocalVariable")
                float f1 = value;
                float f2 = 1.0f - f1;

                // установка коэффициентов (матрицы 5x5) свертки
                float coefficients[] = {
                        -f1*2,  0,      -f1,    0,      0,
                        0,      -f2*2,  -f2,    0,      0,
                        -f1,    -f2,    1,      f2,     f1,
                        0,      0,      f2,     f2*2,   0,
                        0,      0,      f1,     0,      f1*2};
                mScriptConvolve.setCoefficients(coefficients); // передача коэффициентов в фильтр

                mScriptConvolve.setInput(inAllocation); // установка входных данных
                // вызов самого фильтра и запись результатов в выходной Allocation
                mScriptConvolve.forEach(outAllocation);
                break;
            }
        }

        // копируем выходные результаты работы фильтра в битмап изображение
        outAllocation.copyTo(bitmapOut);
    }

    // преобразование установленного значения ползунка (от 0 до 100) в параметр для каждого встроенного фильтра
    private float getFilterParameter(int i) {

        //Log.d(TAG, "метод getFilterParameter" + String.format(Locale.getDefault(), "(%d)", i));

        float f = 0.f;
        switch (mFilterMode) {
            case MODE_BLUR: {  // фильтре Гаусса
                // радиуса должн быть в промежутке от 1.0 до 25.0 пикселей
                final float max = 25.0f;
                final float min = 1.f;
                f = (float) ((max - min) * (i / 100.0) + min);
            }
            break;

            case MODE_CONVOLVE: { // фильтр четкости
                // данные должны быть в промежутке 2.0 - 0.0
                final float max = 2.f;
                final float min = 0.f;
                f = (float) ((max - min) * (i / 100.0) + min);
            }
            break;
        }
        return f;
    }


    // создаем класс потока UI для выполнения фоновых операций без манипуляций обработчиками / потоками
    // в классе AsyncTask вызываем внутреннюю среду RenderScript для фильтрации
    // после выполнения фильтрации, в потоке AsyncTask блокируется Allocation.copyTo()
    private class RenderScriptTask extends AsyncTask<Float, Integer, Integer> {

        private boolean mIssued; // обработан фильтр

        // обновление фонового рисунка на новый по индексу
        void updateView() {
            //Log.d(TAG, "метод updateView" + String.format(Locale.getDefault(), "(%d)", result));
            mImageView.setImageBitmap(mBitmapOut); // устанавливаем битмап
            mImageView.invalidate(); // если View отображается, вызываем onDraw из потока UI
        }

        // метод-событие, выполнение вычислений в фоновом потоке
        @Override
        protected Integer doInBackground(Float... values) {
            //Log.d(TAG, "событие doInBackground(...)");
            if (!isCancelled()) { // если задача НЕ была отменена до ее завершения
                mIssued = true; // делаем пометку что обработали

                performFilter(mInAllocation, mOutAllocation, mBitmapOut, values[0]); // применяем фильтр
            }
            return 1;
        }

        // метод-событие, работает в потоке UI после doInBackground, когда завершаются все операции
        @Override
        protected void onPostExecute(Integer result) {
            //Log.d(TAG, "событие onPostExecute" + String.format(Locale.getDefault(), "(%d)", result));
            updateView(); // обновляет фоновый рисунок
        }

        // метод-событие,  выполняется после отмены и завершает doInBackground
        @Override
        protected void onCancelled(Integer result) {
            //Log.d(TAG, "событие onCancelled" + String.format(Locale.getDefault(), "(%d)", result));
            if (mIssued) { // если фильтр все же успел обработать
                updateView(); // обновляет фоновый рисунок
            }
        }
    }

    // метод вызывает новую задачу - фильтрацию через вызов AsyncTask (RenderScriptTask) и отменяет предыдущую задачу
    // когда AsyncTasks накапливается (как правило, на медленных устройствах с тяжелым ядром), то последняя (и уже запущенная) задача вызывает операцию RenderScript
    private void updateImage(int progress) {
        //Log.d(TAG, "метод updateImage" + String.format(Locale.getDefault(), "(%d)", progress));
        float f = getFilterParameter(progress);

        if (mLatestTask != null) // если задача уже существует
            mLatestTask.cancel(false); // отменяем ее
        mLatestTask = new RenderScriptTask(); // создаем новую задачу

        mLatestTask.execute(f); // запуск задачи
    }

    // создаем thumbnail для UI, он синхронно вызывает ядро RenderScript в UI-потоке, для создание маленьких эскизов кнопок ThumbnailRadioButton
    private void createThumbnail() {
        //Log.d(TAG, "метод createThumbnail(...)");

        int width = 72; // ширина кнопки
        int height = 96; // высота кнопки
        float scale = getResources().getDisplayMetrics().density;
        int pixelsWidth = (int) (width * scale + 0.5f);
        int pixelsHeight = (int) (height * scale + 0.5f);

        // временное изображение
        Bitmap tempBitmap = Bitmap.createScaledBitmap(mBitmapIn, pixelsWidth, pixelsHeight, false);
        Allocation inAllocation = Allocation.createFromBitmap(mRS, tempBitmap);

        // создаем эскиз с внутренним RenderScript и устанавливаем его на RadioButton
        int[] modes = {MODE_BLUR, MODE_CONVOLVE};
        int[] ids = {R.id.radio0, R.id.radio1};
        int[] parameter = {50, 100}; // параметер фильтра который будет установлен у изображения
        for (int mode : modes) {
            mFilterMode = mode;
            float f = getFilterParameter(parameter[mode]);

            Bitmap destBitmap = Bitmap.createBitmap(tempBitmap.getWidth(),
                    tempBitmap.getHeight(), tempBitmap.getConfig());
            Allocation outAllocation = Allocation.createFromBitmap(mRS, destBitmap);
            performFilter(inAllocation, outAllocation, destBitmap, f);

            ThumbnailRadioButton button = (ThumbnailRadioButton) findViewById(ids[mode]);
            button.setThumbnail(destBitmap);
        }
    }

    private class BitmapSaver implements Runnable {

        private final Bitmap mBitmap; // изображение в формате JPEG
        private final File mFile; // файл для сохранения изображения

        BitmapSaver(Bitmap bitmap, File file) {
            mBitmap = bitmap;
            mFile = file;
        }

        // переопределенный метод для выполняется посредством потока
        // метод сохранение файла на диск
        @Override
        public void run() {

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            mBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream);

            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(stream.toByteArray());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}