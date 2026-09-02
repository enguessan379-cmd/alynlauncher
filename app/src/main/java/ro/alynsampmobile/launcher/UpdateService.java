package ro.alynsampmobile.launcher;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.downloader.Error;
import com.downloader.OnDownloadListener;
import com.downloader.PRDownloader;
import com.downloader.PRDownloaderConfig;
import com.downloader.Progress;
import com.joom.paranoid.Obfuscate;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ro.alynsampmobile.launcher.utils.FileData;
import ro.alynsampmobile.launcher.utils.Utils;

@Obfuscate
public class UpdateService extends Service {
    public Messenger mMessenger;
    public IncomingHandler mInHandler;
    public Messenger mActivityMessenger;

    public UpdateActivity.UpdateStatus mUpdateStatus = UpdateActivity.UpdateStatus.Undefined;
    public UpdateActivity.GameStatus mGameStatus = UpdateActivity.GameStatus.Undefined;

    public boolean mDownloadingStatus = false;
    public ArrayList<FileData> mUpdateFiles = new ArrayList<>();
    public long mUpdateFilesSizeTotal = 0;
    public int mDownloadFailedOffset;

    public String mUpdateVersion;
    public boolean mFullDataUpdateRequired = false;

    public void onCreate() {
        HandlerThread thread = new HandlerThread("ServiceStartArguments", 10);
        thread.start();
        PRDownloader.initialize(getApplicationContext(), PRDownloaderConfig.newBuilder().setDatabaseEnabled(true).setReadTimeout(30000).setConnectTimeout(30000).build());
        mInHandler = new IncomingHandler(thread.getLooper());
        mMessenger = new Messenger(mInHandler);
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    public IBinder onBind(Intent intent) {
        if (mMessenger != null) {
            return mMessenger.getBinder();
        }
        return null;
    }

    public boolean onUnbind(Intent intent) {
        return false;
    }

    public void onRebind(Intent intent) {
    }

    public void onDestroy() {
    }

    private final class IncomingHandler extends Handler {
        public IncomingHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            mActivityMessenger = msg.replyTo;
            if (msg.what == 0) checkUpdate();
            else if (msg.what == 1) updateGameFiles();
            else if (msg.what == 2) updateGame();
            else if (msg.what == 4) {
                Message outMsg = Message.obtain(mInHandler, 4);
                outMsg.getData().putString(NotificationCompat.CATEGORY_STATUS, mUpdateStatus.name());
                outMsg.replyTo = mMessenger;
                if (mActivityMessenger != null) {
                    try {
                        mActivityMessenger.send(outMsg);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            } else if (msg.what == 5) {
                Message outMsg = Message.obtain(mInHandler, 5);
                outMsg.getData().putString(NotificationCompat.CATEGORY_STATUS, mGameStatus.name());
                outMsg.replyTo = mMessenger;
                if (mActivityMessenger != null) {
                    try {
                        mActivityMessenger.send(outMsg);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            } else if (msg.what == 7) {
                Log.i("UpdateService", "UPDATE_STATUS_GAME");
                checkUpdate();
            } else if (msg.what == 8) {
                checkUpdate();
            }
        }
    }

    public void checkUpdate() {
        Log.d("UpdateService", "checkUpdate()");
        setUpdateStatus(UpdateActivity.UpdateStatus.CheckUpdate);

        try {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url(Utils.update)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        assert response.body() != null;
                        String responseBody = response.body().string();
                        JSONObject data = new JSONObject(responseBody);

                        mUpdateVersion = data.getString("game_version");
                        Log.i("UpdateService", "mUpdateVersion = " + mUpdateVersion);

                        mUpdateFiles = new ArrayList<>();

                        mGameStatus = UpdateActivity.GameStatus.Undefined;
                        Message outMsg = Message.obtain(mInHandler, 10);
                        outMsg.replyTo = mMessenger;
                        if (mActivityMessenger != null) {
                            try {
                                mActivityMessenger.send(outMsg);
                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }

                        String filesType = getSharedPreferences("samp_settings", Context.MODE_PRIVATE).getString("files_type", "none");

                        if (filesType.equals("full")) {
                            checkFullDataUpdate();
                        } else {
                            String data_url = data.getString("lite_list_url");
                            String samp_data_url = data.getString("samp_list_url");
                            checkGameFilesUpdate(data_url, samp_data_url);
                        }

                        if (!isGamePackageExists()) {
                            mGameStatus = UpdateActivity.GameStatus.GameUpdateRequired;
                        } else if (isGameUpdateExists() && !BuildConfig.DEBUG) {
                            mGameStatus = UpdateActivity.GameStatus.GameUpdateRequired;
                        } else if (isGameFilesUpdateExists()) {
                            mGameStatus = UpdateActivity.GameStatus.GameFilesUpdateRequired;
                        } else {
                            mGameStatus = UpdateActivity.GameStatus.Updated;
                        }

                        setUpdateStatus(UpdateActivity.UpdateStatus.Undefined);
                    } catch (Exception e) {
                        Log.e("UpdateService", Objects.requireNonNull(e.getMessage()));
                        mGameStatus = UpdateActivity.GameStatus.Undefined;
                        Message outMsg = Message.obtain(mInHandler, 5);
                        outMsg.getData().putString(NotificationCompat.CATEGORY_STATUS, mGameStatus.name());
                        outMsg.replyTo = mMessenger;
                        if (mActivityMessenger != null) {
                            try {
                                mActivityMessenger.send(outMsg);
                            } catch (RemoteException ee) {
                                ee.printStackTrace();
                            }
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    mGameStatus = UpdateActivity.GameStatus.Undefined;
                    Message outMsg = Message.obtain(mInHandler, 5);
                    outMsg.getData().putString(NotificationCompat.CATEGORY_STATUS, mGameStatus.name());
                    outMsg.replyTo = mMessenger;
                    if (mActivityMessenger != null) {
                        try {
                            mActivityMessenger.send(outMsg);
                        } catch (RemoteException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            });
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    public boolean isGameUpdateExists() {
        PackageInfo packageInfo;
        Log.i("UpdateService", "isGameUpdateExists");
        PackageManager packageManager = getPackageManager();
        String currentVersion = null;
        if (packageManager != null) {
            try {
                packageInfo = packageManager.getPackageInfo(getPackageName(), PackageManager.GET_ACTIVITIES);
            } catch (PackageManager.NameNotFoundException e) {
                return true;
            }
        } else {
            packageInfo = null;
        }
        if (packageInfo != null) {
            currentVersion = packageInfo.versionName;
        }
        String sb = "isGameUpdateExists -> currentVersion " + currentVersion + " | mUpdateVersion " + mUpdateVersion;
        Log.d("UpdateService", sb);
        return (currentVersion == null || !currentVersion.equals(mUpdateVersion));
    }

    public boolean isGamePackageExists() {
        try {
            getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return true;
    }

    /**
     * Builds mUpdateFiles with every remote file that is missing locally or whose
     * size doesn't match what's on disk. dataUrl is the game files list (full/lite),
     * sampDataUrl is the samp-specific files list; both use the same JSON format.
     */
    private void checkGameFilesUpdate(String dataUrl, String sampDataUrl) {
        mUpdateFiles = new ArrayList<>();
        mUpdateFilesSizeTotal = 0;

        try {
            ArrayList<FileData> remoteFiles = new ArrayList<>();
            remoteFiles.addAll(FileData.getListByJson(new JSONObject(Utils.getStringOutputByURL(dataUrl))));
            remoteFiles.addAll(FileData.getListByJson(new JSONObject(Utils.getStringOutputByURL(sampDataUrl))));

            for (FileData fileData : remoteFiles) {
                // Skip files meant for a different texture compression (gpu) than this device uses.
                if (fileData.getGpu() != null && !fileData.getGpu().isEmpty()
                        && !fileData.getGpu().equalsIgnoreCase("none")
                        && !fileData.getGpu().equalsIgnoreCase(Utils.GPU_TYPE.name())) {
                    continue;
                }

                File localFile = new File(getExternalFilesDir(null), fileData.getPath());
                if (!localFile.exists() || localFile.length() != fileData.getSize()) {
                    mUpdateFiles.add(fileData);
                    mUpdateFilesSizeTotal += fileData.getSize();
                }
            }
        } catch (Exception e) {
            Log.e("UpdateService", "checkGameFilesUpdate: " + e.getMessage());
        }
    }

    public boolean isGameFilesUpdateExists() {
        String filesType = getSharedPreferences("samp_settings", Context.MODE_PRIVATE).getString("files_type", "none");
        if (filesType.equals("full")) {
            return mFullDataUpdateRequired;
        }
        return mUpdateFiles != null && !mUpdateFiles.isEmpty();
    }

    /**
     * "Full" mode doesn't diff individual files: it just checks whether the full data zip
     * matching mUpdateVersion has already been downloaded and extracted before.
     */
    private void checkFullDataUpdate() {
        String installedVersion = getSharedPreferences("samp_settings", Context.MODE_PRIVATE).getString("full_data_version", "");
        mFullDataUpdateRequired = mUpdateVersion == null || !mUpdateVersion.equals(installedVersion);
    }

    /**
     * Downloads every file queued in mUpdateFiles (built by checkGameFilesUpdate),
     * reporting progress the same way UpdateActivity expects for UpdateStatus.DownloadGameFiles.
     */
    public void updateGameFiles() {
        String filesType = getSharedPreferences("samp_settings", Context.MODE_PRIVATE).getString("files_type", "none");
        if (filesType.equals("full")) {
            if (!mFullDataUpdateRequired) {
                setUpdateStatus(UpdateActivity.UpdateStatus.Undefined);
                sendUpdateFinished(true);
                return;
            }
            mDownloadingStatus = true;
            setUpdateStatus(UpdateActivity.UpdateStatus.DownloadGameFiles);
            downloadFullDataZip();
            return;
        }

        if (mUpdateFiles == null || mUpdateFiles.isEmpty()) {
            setUpdateStatus(UpdateActivity.UpdateStatus.Undefined);
            sendUpdateFinished(true);
            return;
        }

        mDownloadingStatus = true;
        mDownloadFailedOffset = 0;
        setUpdateStatus(UpdateActivity.UpdateStatus.DownloadGameFiles);
        downloadNextGameFile(0, 0);
    }

    /**
     * Downloads the full data zip and extracts it into the game's external files dir.
     */
    private void downloadFullDataZip() {
        File zipFile = new File(getCacheDir(), "full_data.zip");
        if (zipFile.exists()) {
            zipFile.delete();
        }

        PRDownloader.download(Utils.fullDataZipUrl, zipFile.getParent(), zipFile.getName())
                .build()
                .setOnProgressListener((Progress progress) -> sendDownloadProgress(
                        progress.totalBytes,
                        progress.currentBytes,
                        "Crestwood.zip",
                        1,
                        1))
                .start(new OnDownloadListener() {
                    @Override
                    public void onDownloadComplete() {
                        extractFullDataZip(zipFile);
                    }

                    @Override
                    public void onError(Error error) {
                        Log.e("UpdateService", "downloadFullDataZip: failed to download full data zip");
                        mDownloadingStatus = false;
                        setUpdateStatus(UpdateActivity.UpdateStatus.Undefined);
                        sendUpdateFinished(false);
                    }
                });
    }

    private void extractFullDataZip(File zipFile) {
        setUpdateStatus(UpdateActivity.UpdateStatus.ExtractingFiles);

        new Thread(() -> {
            try {
                Utils.unzip(zipFile, getExternalFilesDir(null), (current, total, name) ->
                        sendExtractProgress(total, current, name));

                zipFile.delete();

                getSharedPreferences("samp_settings", Context.MODE_PRIVATE).edit()
                        .putString("full_data_version", mUpdateVersion)
                        .apply();

                mDownloadingStatus = false;
                setUpdateStatus(UpdateActivity.UpdateStatus.Undefined);
                sendUpdateFinished(true);
            } catch (IOException e) {
                Log.e("UpdateService", "extractFullDataZip: " + e.getMessage());
                mDownloadingStatus = false;
                setUpdateStatus(UpdateActivity.UpdateStatus.Undefined);
                sendUpdateFinished(false);
            }
        }).start();
    }

    private void sendExtractProgress(int total, int current, String filename) {
        Message outMsg = Message.obtain(mInHandler, 4);
        outMsg.getData().putString(NotificationCompat.CATEGORY_STATUS, UpdateActivity.UpdateStatus.ExtractingFiles.name());
        outMsg.getData().putBoolean("withProgress", true);
        outMsg.getData().putLong("total", total);
        outMsg.getData().putLong("current", current);
        outMsg.getData().putString("filename", filename);
        outMsg.replyTo = mMessenger;
        if (mActivityMessenger != null) {
            try {
                mActivityMessenger.send(outMsg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void downloadNextGameFile(int index, long bytesDownloadedSoFar) {
        if (index >= mUpdateFiles.size()) {
            mDownloadingStatus = false;
            setUpdateStatus(UpdateActivity.UpdateStatus.Undefined);
            sendUpdateFinished(mDownloadFailedOffset == 0);
            return;
        }

        FileData fileData = mUpdateFiles.get(index);
        File destination = new File(getExternalFilesDir(null), fileData.getPath());
        if (destination.getParentFile() != null) {
            destination.getParentFile().mkdirs();
        }

        final int totalFiles = mUpdateFiles.size();
        final int currentFileNumber = index + 1;
        final long bytesBefore = bytesDownloadedSoFar;

        PRDownloader.download(fileData.getUrl(), destination.getParent(), destination.getName())
                .build()
                .setOnProgressListener((Progress progress) -> sendDownloadProgress(
                        mUpdateFilesSizeTotal,
                        bytesBefore + progress.currentBytes,
                        fileData.getName(),
                        totalFiles,
                        currentFileNumber))
                .start(new OnDownloadListener() {
                    @Override
                    public void onDownloadComplete() {
                        downloadNextGameFile(index + 1, bytesBefore + fileData.getSize());
                    }

                    @Override
                    public void onError(Error error) {
                        mDownloadFailedOffset++;
                        Log.e("UpdateService", "updateGameFiles: failed to download " + fileData.getName());
                        downloadNextGameFile(index + 1, bytesBefore + fileData.getSize());
                    }
                });
    }

    private void sendDownloadProgress(long total, long current, String filename, long totalFiles, long currentFile) {
        Message outMsg = Message.obtain(mInHandler, 4);
        outMsg.getData().putString(NotificationCompat.CATEGORY_STATUS, UpdateActivity.UpdateStatus.DownloadGameFiles.name());
        outMsg.getData().putBoolean("withProgress", true);
        outMsg.getData().putLong("total", total);
        outMsg.getData().putLong("current", current);
        outMsg.getData().putString("filename", filename);
        outMsg.getData().putLong("totalfiles", totalFiles);
        outMsg.getData().putLong("currentfile", currentFile);
        outMsg.replyTo = mMessenger;
        if (mActivityMessenger != null) {
            try {
                mActivityMessenger.send(outMsg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendUpdateFinished(boolean success) {
        Message finishMsg = Message.obtain(mInHandler, 1);
        finishMsg.getData().putBoolean(NotificationCompat.CATEGORY_STATUS, success);
        finishMsg.replyTo = mMessenger;
        if (mActivityMessenger != null) {
            try {
                mActivityMessenger.send(finishMsg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void setUpdateStatus(UpdateActivity.UpdateStatus status) {
        mUpdateStatus = status;
        Message outMsg = Message.obtain(mInHandler, 4);
        outMsg.getData().putString(NotificationCompat.CATEGORY_STATUS, status.name());
        outMsg.replyTo = mMessenger;
        if (mActivityMessenger != null) {
            try {
                mActivityMessenger.send(outMsg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void updateGame() {
        setUpdateStatus(UpdateActivity.UpdateStatus.Undefined);
        Message finishMsg = Message.obtain(mInHandler, 2);
        finishMsg.getData().putBoolean(NotificationCompat.CATEGORY_STATUS, true);
        finishMsg.replyTo = mMessenger;
        Messenger messenger = mActivityMessenger;
        if (messenger != null) {
            try {
                messenger.send(finishMsg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
