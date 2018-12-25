package com.example.android.camerafilter;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent; // вызов второй активности
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log; // для записи в лог
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.UUID; // для генерации случайного имени

public class CameraActivity extends AppCompatActivity
        implements View.OnClickListener, // объявляем обработчик нажатия кнопки View.OnClickListener
        ActivityCompat.OnRequestPermissionsResultCallback {  // объявляем обработчик результатов запросов на разрешение (права доступа) 

     // преобразование из поворота экрана до ориентации JPEG
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray(); // создаем контейнер ассоциативный массив целых значений
    // наполняем массив сопоставлений ORIENTATIONS
    // корректировок ориентаций поверхности и ориентации JPEG
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private static final String FRAGMENT_DIALOG = "dialog";
    private static final String TAG = "CameraActivity"; // тег для лога Log

    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final int REQUEST_EXTERNAL_STORAGE_PERMISSION = 2;
    private static final int STATE_PREVIEW = 0; // состояние камеры: отображение предпросмотра камеры
    private static final int STATE_WAITING_LOCK = 1; // состояние камеры: ожидание блокировки фокуса
    private static final int STATE_WAITING_PRECAPTURE = 2; // состояние камеры: ожидание экспозиции в состоянии предварительного захвата
    private static final int STATE_WAITING_NON_PRECAPTURE = 3; // состояние камеры: ожидание состояния экспозиции но не предварительный захват
    private static final int STATE_PICTURE_TAKEN = 4; // состояние камеры: получена фотография
    private static final int MAX_PREVIEW_WIDTH = 1920; // максимальная ширина предпросмотра, гарантированная Camera2 API
    private static final int MAX_PREVIEW_HEIGHT = 1080; // максимальная высота предпросмотра, гарантированная Camera2 API

    private String mCameraId; // название ID текущей камерыCameraDevice
    // просмотр потока с определенным соотношением сторон, наследует от TextureView
    private AutoFitTextureView mTextureView; // предварительный просмотр камеры AutoFitTextureView
    
    // сеанс захвата изображений из CameraDevice 
    private CameraCaptureSession mCaptureSession; // ссесия захвата для предпросмотра камеры
    private CameraDevice mCameraDevice; // ссылка на открытую камеру CameraDevice
    private Size mPreviewSize; // размеры android.util.Size экрана предпросмотра

    private CaptureRequest.Builder mPreviewRequestBuilder; // создатель запросов на захват для предпросмотра камеры
    private CaptureRequest mPreviewRequest; // {@link CaptureRequest} который сгенерируется из {@link #mPreviewRequestBuilder}
    private int mState = STATE_PREVIEW; //  текущее состояние камеры для съемки через обработчик mCaptureCallback
    
    private Semaphore mCameraOpenCloseLock = new Semaphore(1); // для предотвращения выхода приложения перед закрытием камеры (объект Semaphore)
    private boolean mFlashSupported; // поддерживает устройство вспышку или нет.
    private int mSensorOrientation; // значение датчика ориентации камеры
    
    private HandlerThread mBackgroundThread; // дополнительный поток для запуска задач, которые не должны блокировать UI
    private Handler mBackgroundHandler; // поток для запуска задач в фоновом режиме
    private ImageReader mImageReader; // чтение изображения, обрабатка- захват неподвижных изображений
    private File mFile; //  выходной файл для нашего изображения

    // событие-метод когда создаем активность
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //Log.d(TAG, "событие onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera); // отображаем активность на экране

        findViewById(R.id.picture).setOnClickListener(this); // назначение метода при нажатии на ресурс picture
        findViewById(R.id.info).setOnClickListener(this); // назначение метода при нажатии на ресурс info

        mTextureView = findViewById(R.id.texture);
    }

    // событие-метод когда отображаем активность
    @Override
    public void onResume() {
        //Log.d(TAG, "событие onResume()");
        super.onResume();

        startBackgroundThread(); // запускаем фоновые потоки
        // когда экран выключен и снова включен, SurfaceTexture уже доступен, а onSurfaceTextureAvailable не будет вызван
        // в этом случае можем открыть камеру и начать просмотр, иначе ждем пока поверхность не будет готова в SurfaceTextureListener
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    // событие-метод когда покидаем активность
    @Override
    public void onPause() {
        //Log.d(TAG, "событие onPause()");
        super.onPause();

        closeCamera(); // закрываем камеру
        stopBackgroundThread(); // останавливаем фоновые потоки
    }

    //  событие-метод когда нажата кнопока
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            
            case R.id.picture: { // если был нажат ресурс картинки кнопки
                takePicture(); // запуск захвата неподвижного изображения
                break;
            }

            case R.id.info: {  // если был нажата кнопка info
                new AlertDialog.Builder(this)
                    .setMessage(R.string.intro_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    // обработчик TextureView.SurfaceTextureListener, обработка события текстуры предпросмотра
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        // событие-метод когда SurfaceTexture от TextureView's готов к использованию
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            //Log.d(TAG, "событие onSurfaceTextureAvailable" + String.format(Locale.getDefault(), "(..%d, %d)", width, height));
            openCamera(width, height);
        }

        // событие-метод когда размер буфера SurfaceTexture изменится, т.е. когда был поворот экрана
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            //Log.d(TAG, "событие onSurfaceTextureSizeChanged" + String.format(Locale.getDefault(), "(..%d, %d)", width, height));
            configureTransform(width, height); // конфигурируем поворот
        }

        // событие-метод когда SurfaceTexture будет уничтожено
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        // событие-метод когда SurfaceTexture обновляется через updateTexImage ()
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }
    }; // конец TextureView.SurfaceTextureListener
    // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    // обработчик событий камеры CameraDevice.StateCallback, когда камера CameraDevice изменяет свое состояние
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        // событие-метод когда завершается открытие камеры
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            //Log.d(TAG, "событие onOpened(...)");
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession(); // запуск сеанса захвата изображения с CameraDevice
        }
        
        // событие-метод когда камера больше не доступна для использования
        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            //Log.d(TAG, "событие onDisconnected(...)");
            mCameraOpenCloseLock.release();
            cameraDevice.close(); // закрытие сеанса захвата с CameraDevice
            mCameraDevice = null;
        }
        
        // событие-метод когда камера выявила серьезную ошибку 
        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close(); // закрытие сеанса захвата с CameraDevice
            mCameraDevice = null;

            finish();
        }
    }; // конец CameraDevice.StateCallback
    // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //  обработчик ImageReader.OnImageAvailableListener, когда доступно новое изображение
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {

        // событие-метод когда неподвижное изображение доступно
        // выполняем создание имени файла и его сохранения
        @Override
        public void onImageAvailable(ImageReader reader) {
            //Log.d(TAG, "событие onImageAvailable(...)");

            // проверяем наличие прав доступа к памяти
            if (ContextCompat.checkSelfPermission(CameraActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestWriteExternalStoragePermission();
                return;
            }

            // через конструктор File создаем путь в галерею фотографий
            File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            String uniqueID = UUID.randomUUID().toString(); // и задаем случайное имя файла без расширения

            mFile = new File(path,uniqueID + ".jpg");
            mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mFile));
        }
    }; // конец ImageReader.OnImageAvailableListener
    // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
     // обработчик CameraCaptureSession.CaptureCallback, когда произошло событие связанные с захватом JPEG
    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {

        // метод обработки текущих состояний камеры(mState)  и текущих статусов работы захвата изображения (CaptureResult)
        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: { // предпросмотр камеры
                    // ничего не делаем, просмотр камеры работает нормально
                    break;
                }
                case STATE_WAITING_LOCK: { //  состояние камеры: ожидание блокировки фокуса
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE); // сохраняем  результат поля CONTROL_AF_STATE, работы авто-фокусного (AF) алгоритма
                    if (afState == null) { // если статус авто-фокуса не указан
                        captureStillPicture(); // запуск захвата неподвижного изображения
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState || // произошло заблокирование фокуса (произошла хорошая фокусировка)
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) { // не произошло заблокирование фокуса (нет возможности получить хороший фокус)
                        // CONTROL_AE_STATE может быть пустым на некоторых устройствах
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE); // сохраняем  результат поля CONTROL_AE_STATE, работы алгоритма автоэкспозиции (AE)
                        if (aeState == null || // если статус автоэкспозиции не указан или если
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) { //  автоэкспозиция имеет хороший набор контрольных значений для текущей сцены
                            mState = STATE_PICTURE_TAKEN; // устанавливаем статус камеры: картинка получена
                            captureStillPicture(); // запуск захвата неподвижного изображения
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: { // состояние камеры: ожидание экспозиции в состоянии предварительного захвата
                    // CONTROL_AE_STATE может быть нулевым на некоторых устройствах
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);  // сохраняем  результат поля CONTROL_AE_STATE, работы алгоритма автоэкспозиции (AE)
                    if (aeState == null || // если статус автоэкспозиции не указан или если
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || // алгоритм автоэкспозиции (AE) начал работу по  выполнению и после завершения перейдет в статус ..._CONVERGED
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) { // алгоритм автоэкспозиции (AE) имеет хороший набор контрольных значений, но вспышка  не требуется
                        mState = STATE_WAITING_NON_PRECAPTURE; // устанавливаем статус камеры: ожидание состояния экспозиции но не предварительный захват
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: { // состояние камеры: ожидание состояния экспозиции но не предварительный захват
                    // CONTROL_AE_STATE может быть нулевым на некоторых устройствах
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE); // сохраняем  результат поля CONTROL_AE_STATE, работы алгоритма автоэкспозиции (AE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) { // алгоритм автоэкспозиции (AE) начал работу по  выполнению и после завершения перейдет в статус ..._CONVERGED
                        mState = STATE_PICTURE_TAKEN; // устанавливаем статус камеры: картинка получена
                        captureStillPicture(); // запуск захвата неподвижного изображения
                    }
                    break;
                }
            }
        }

        // событие-метод когда начат процесс захвата JPEG и уже кое-что захвачено
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            //Log.d(TAG, "событие onCaptureProgressed()");
            process(partialResult); // запуск обработки текущих состояний камеры
        }

        // событие-метод когда процесс захвата JPEG завершен
        // цикл сообщений
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result); // запуск обработки текущих состояний камеры
        }
    };  // конец CameraCaptureSession.CaptureCallback
// ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

     // Параметер {@code choices} типа {@code Size}, это список разрешений поддерживаемых камерой, выбор наименьших размеров которые не меньше размера соответствующей текстуры,
	 // и самые большие из максимальных размеров, и соотношение сторон которых соответствует указанному значению.
	 // Если такого размера не существует, выберите наибольший размер, который не превосходит соответствующий максимальный размер,
	 //  и соотношение сторон которого соответствует указанному значению
     
     // choices - список размеров поддерживаемых камерой для предполагаемого класса вывода
     // textureViewWidth - ширина представления текстуры относительно координаты датчика
     // textureViewHeight - высота представления текстуры относительно координаты датчика
     // maxWidth - максимальная ширина которую можно выбрать
     // maxHeight - максимальная высота которую можно выбрать
     // aspectRatio - соотношение сторон
     // возвращает оптимальный размер Size, или произвольный (если не найден достаточно большой размер)
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth, int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        //Log.d(TAG, "метод chooseOptimalSize" + String.format(Locale.getDefault(), "(..%d, %d, %d, %d..)", textureViewWidth, textureViewHeight, maxWidth, maxHeight));

        // создаем список поддерживаемых разрешений
        List<Size> bigEnough = new ArrayList<>();  // которые большие текстуры предпросмотра Surface
        List<Size> notBigEnough = new ArrayList<>(); // которые меньше текстуры предпросмотра Surface
        int w = aspectRatio.getWidth(); // получаем длину отношений сторон
        int h = aspectRatio.getHeight(); //  получаем ширину отношений сторон
        for (Size option : choices) {  // цикл перебор списка  размеров (опций) для выбора
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                    option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option); // добавляем в список опций которые больше окна предпросмотра
                } else {
                    notBigEnough.add(option); // добавляем в список опций которые меньше окна предпросмотра
                }
            }
        }

        // выбор самого малого размера из достаточно больших, если нет подходящего достаточно большого, то выбераем самый большой из списка не достаточно больших
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "chooseOptimalSize(): не удалось найти подходящие размеры для предпросмотра");
            return choices[0];
        }
    }

    // метод запроса разрешений на камеру
    @TargetApi(Build.VERSION_CODES.M)
    private void requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new ConfirmationDialog().show(getSupportFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    // метод запроса разрешений на память
    @TargetApi(Build.VERSION_CODES.M)
    private void requestWriteExternalStoragePermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            new ConfirmationDialog().show(getSupportFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXTERNAL_STORAGE_PERMISSION);
        }
    }

    // обработчик полученного ответа при запросе на доступ
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION || requestCode == REQUEST_EXTERNAL_STORAGE_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(getSupportFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // метод открытия камеры, указанной в mCameraId объекта CameraFragment
    private void openCamera(int width, int height) {

        //Log.d(TAG, "метод openCamera" + String.format(Locale.getDefault(), "(%d, %d)", width, height));

        // проверяем наличие прав доступа к камере
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }
        setUpCameraOutputs(width, height);
        configureTransform(width, height);

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Вышло время ожидания для блокировки открытия камеры.");
            }
            // открываем соединение с камерой, связываем ее с обработчиком событий камеры mStateCallback
            // mBackgroundHandler, поток для обработкифотоных задач
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Ошибка из-за попытки заблокировать открытие камеры.", e);
        }
    }

    // метод устанавки переменные-члены связанные с камерой.
    // width, height - ширина, высота доступного размера для предпросмотра у камеры
    @SuppressWarnings("SuspiciousNameCombination")
    private void setUpCameraOutputs(int width, int height) {

        //Log.d(TAG, "метод setUpCameraOutputs" + String.format(Locale.getDefault(), "(%d, %d)", width, height));
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) { // цикл перебор списка всех ID камер из списка подключенных к устройству камер
                // получаем доступ к интерфейсу характеристик камерыс заданным ID  
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

				// получаем св-ва текущей камеры, а именно направление камеры относительно экрана устройств
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && // если камеры нет, или
					facing == CameraCharacteristics.LENS_FACING_FRONT) { // если камера смотрит туда же куда и экран (фронтальная) то 
                    continue; // не используем такую переднюю камеру здесь
                }

				// получаем св-ва текущей камеры, а именно доступные конфигурации потоков поддерживаемые этой камерой
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP); 
                if (map == null) { // если у текущей камеры нет данных - переходим к следущей
                    continue;
                }

                // ищем самый большой доступный размер для хранения неподвижных изображений
                Size largest = Collections.max( // ищем максимум среди коллекции 
                        Arrays.asList(map.getOutputSizes( // где коллекция - список из массива, содержащий выходные размеры которые поддерживаются камерой
                            ImageFormat.JPEG)), // для выходного формата типа JPEG
                        new CompareSizesByArea()); // ищется по сравнению по площади

                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), // устанавливаем считыватель заданного разрешения 
                        ImageFormat.JPEG, /*maxImages*/2); // и формата JPEG, и максимального кол-ва изображений полученного из считывателя

                //Log.d(TAG, "max параметры камеры" + String.format(Locale.getDefault(), "(%d, %d)", largest.getWidth(), largest.getHeight()));

                // регистрируем  обработчик mOnImageAvailableListener для вызова когда новое изображение станет доступным
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, 
                                mBackgroundHandler); // поток mBackgroundHandler в котором будет вызыватся слушатель

                // узнаем нужно ли менять между собой размеры чтобы получить размер предпросмотра относительно координаты датчика
                int displayRotation = getWindowManager().getDefaultDisplay().getRotation(); // получаем ориентацию экрана устройства
                // нет проверки ConstantConditions
				// определяем угол по часов.стрелке на которое нужно повернуть выходое изображение что бы оно было вертикально на экране устройства в естест. ориентации 
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION); 
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "ошибка при вращении дисплея: " + displayRotation);
                }

                Point displaySize = new Point();
                getWindowManager().getDefaultDisplay().getSize(displaySize);

                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                // если размеры эрана (для предпросмотра) превышает установленные пределы, устанавливаем в размерах этих пределов
                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) { maxPreviewWidth = MAX_PREVIEW_WIDTH; }
                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) { maxPreviewHeight = MAX_PREVIEW_HEIGHT; }

                // Важно! Использование слишком большого размера предпросмотра может превышать ограничение ширины полосы пропускания камеры.
				// Это приводит к качественной картинке предпросмотра но замусоривает устройство данными захвата
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);

                // сопоставляем пропорции TextureViewс размерами выбранного предпросмотра
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    //Log.d(TAG, "горизонтальная AspectRatio");
                    mTextureView.setAspectRatio(
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    //Log.d(TAG, "вертикальная AspectRatio");
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }
				// проверяем поддерживается ли вспышка
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE); //
                mFlashSupported = available == null ? false : available; // сохраняем результат

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // NPE сбрасывается когда Camera2API используется, но не поддерживается на устройстве, этот код работает.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getSupportFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    // метод настройки матрицы преобразования (android.graphics.Matrix) для применения к mTextureView в предварительный просмотр камеры
    // выполняется после определения размера предпросмотра камеры в setUpCameraOutputs, а также корректирования размера mTextureView. 
    // viewWidth - ширина, viewHeight - высота текстуры mTextureView
    private void configureTransform(int viewWidth, int viewHeight) {

        //Log.d(TAG, "метод configureTransform" + String.format(Locale.getDefault(), "(%d, %d)", viewWidth, viewHeight));
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation(); // получаем ориентацию экрана устройства
        Matrix matrix = new Matrix(); // создаем матрицу преобразования
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        // обрабатываем поворот
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) { // поворот на бок: 90, 270 градусов
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);

            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());

            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);

        } else if (Surface.ROTATION_180 == rotation) { // поворот кверх ногами: 180 градусов
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    // метод закрытия камеры mCameraDevice
    private void closeCamera() {

        //Log.d(TAG, "метод closeCamera()");
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) { // если ссессия захвата предпросмотра существует
                mCaptureSession.close(); // закрываем
                mCaptureSession = null;
            }
            if (null != mCameraDevice) { // если открытая камера существует
                mCameraDevice.close(); // закрываем
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Ошибка из-за попытки заблокировать закрытие камеры.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    // метод запускает фоновый поток Handler и его связи
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
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

    // метод создания новой сессии захвата камеры CameraCaptureSession для реализации предпросмотра камеры
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // настраиваем размер буфера по умолчанию как желаемый размер предпросмотра камеры
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // это выходной Surface для запуска предпросмотра
            Surface surface = new Surface(texture);

            // устанавливаем CaptureRequest.Builder с выходной поверхностью
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // создаем CameraCaptureSession для предпросмотра камеры
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
            // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
                    new CameraCaptureSession.StateCallback() {

                        // событие-метод когда устройство камеры завершило настройку и сессия может начинать обработку запросов на захват 
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            
                            if (null == mCameraDevice) { // если камера уже закрыта
                                return;
                            }

                            // когда сессия готова, стартуем отображения предпросмотра
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // автофокус должен быть непрерывным для предпросмотра камеры
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                
                                setAutoFlash(mPreviewRequestBuilder); // вспышка автоматически включается при необходимости

                                // в итоге начинаем показывать предварительный просмотр камеры
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                        // событие-метод когда сеанс не может быть настроен по запросу
                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("Ошибка захвата камеры"); // выводим тост с сообщением об ошибке
                        }
                    }, null
            ); // конец CameraCaptureSession.StateCallback()
            // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------            
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // метод запуска захвата неподвижного изображения
    private void takePicture() {
        lockFocus();
    }

    // метод блокирования фокуса (выполяем перед захватом неподвижного изображения)
    private void lockFocus() {
        try {
            // камера должна блокировать фокус
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // сообщаем #mCaptureCallback ждать блокировки
            mState = STATE_WAITING_LOCK;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // метод запуска последовательности предварительного захвата неподвижного изображения.
    // этот метод следует вызывать когда получаем ответ mCaptureCallback из lockFocus ().
    private void runPrecaptureSequence() {
        try {
            // камера должна запускаться
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // сообщаем #mCaptureCallback подождать, пока будет выполнена сессия предварительного кодирования.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    
     // метод захвата неподвижного изображения
     // вызывается когда получаем ответ в mCaptureCallback из lockFocus ()
    private void captureStillPicture() {
        try {
            if (null == mCameraDevice) {
                return;
            }
            // используем CaptureRequest.Builder чтобы сделать снимок
            final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // в качестве предпросмотра используйте те же режимы AE и AF
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(captureBuilder);

            // ориентация
            int rotation = getWindowManager().getDefaultDisplay().getRotation(); // получаем ориентацию экрана устройства
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
            // обработчик CameraCaptureSession для отслеживания CaptureRequest
            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {
                // событие-метод когда завершен захват изображения и доступны все метаданные результатов
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    //showToast("Снимок сохранен в файл: " + mFile); // выводим тост с сообщением о сохранении файла
                    unlockFocus(); // разблокируем фокус

                    // вызов активности фильтрации
                    Intent intent = new Intent(CameraActivity.this, FilterActivity.class);
                    intent.putExtra("foto_path", mFile.getAbsolutePath());
                    startActivity(intent);

                    Log.d(TAG, mFile.toString()); // добавляем сообщение в лог
                }
            }; // конец CameraCaptureSession
            // ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
            mCaptureSession.stopRepeating();
            mCaptureSession.abortCaptures();
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // метод получает ориентацию JPEG из указанного поворота экрана
    // rotation - поворот экрана, возвращает ориентацию JPEG - 0, 90, 270, 360
    private int getOrientation(int rotation) {
        // учитываем  при повороте JPEG что ориентация датчика составляет 90 для большинства устройств или 270 для некоторых устройств (например, Nexus 5X)
        // для устройств с ориентацией 90 мы просто возвращаем значение из массива ORIENTATIONS
        // для устройств с ориентацией 270 мы поворачиваем на 180 градусов
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    // метод разблокировки фокуса
    // вызывается когда завершен процесс захвата неподвижного изображения
    private void unlockFocus() {
        try {
            // сброс триггера автофокуса
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setAutoFlash(mPreviewRequestBuilder);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            // после, камера возвращается к нормальному состоянию предпросмотра
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

     // класс обработки изображения JPEG виде Image фильтром указанным в файле File
    // наследуется от класса Runnable для выполнения оперций через поток 
    private class ImageSaver implements Runnable {

        private final Image mImage; // изображение в формате JPEG
        private final File mFile; // файл для сохранения изображения

         ImageSaver(Image image, File file) {
            mImage = image;
            mFile = file;
        }

		// переопределенный метод для выполняется посредством потока
		// метод сохранение файла на диск
        @Override
        public void run() {

            // конвертируем из Image в байтовый массив для записи
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer(); // получаем массив пикселей из буфера Image и сохраняем в байтовом буффере

            byte[] bytes = new byte[buffer.remaining()]; // создаем массив байтов для записи из байтового буфера
            buffer.get(bytes); // забисываем и байтового буффера в массив байтов

            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

	// метод установки режима автовспышки
    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
    
        if (mFlashSupported) { // если вспышка поддерживается
        // используем CaptureRequest.Builder чтобы сделать снимок с автоспышкой
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, // режим автоматической экспозиции камеры
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH); // активный режим автом. управления экспозиции камеры, с управлением автовспышкой
        }
    }
    
    // класс сравнения двух параметров {@code Size} по площади
    // @return - возвращаем разницу площадей первого и второго
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // делаем так что бы избежать переполнения которое может быть при умножении
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }

    }
    
    // метод отображения короткого виджет-сообщение Toast ("тост") в потоке пользовательского интерфейса
	// @param text - сообщение для показа
    private void showToast(final String text) {

        runOnUiThread(new Runnable() { // запускаем действие внутри потока UI, через новый интерфейс,
            @Override
            public void run() { // метод которого выполняет следующее
                Toast.makeText(CameraActivity.this, text, Toast.LENGTH_SHORT)  // создает в активности сообщение с текстом text, и длина промежутка времени показа - короткая
                        .show(); // и отображаем его
            }
        });
    }

    // класс отображения диалогового окна с сообщением об ошибке
    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        // событие-метод вызывается перед onCreate(Bundle) и после Fragment.onCreateView
        // собственное диалоговое сообщение
        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
        
            final Activity activity = getActivity(); // получаем ссылку на активность текущего фрагмента

            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }
        
    }

    // класс отображения диалога подтверждения разрешения на получение доступа к камере
    public static class ConfirmationDialog extends DialogFragment {

        @NonNull
        // событие-метод вызывается перед onCreate(Bundle) и после Fragment.onCreateView
        // собственное диалоговое сообщение
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
        
            final Fragment parent = getParentFragment();

            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            parent.requestPermissions(new String[]{Manifest.permission.CAMERA},
                                    REQUEST_CAMERA_PERMISSION);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Activity activity = parent.getActivity();
                                    if (activity != null) {
                                        activity.finish();
                                    }
                                }
                            })
                    .create();
        }
    }
}
