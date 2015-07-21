package com.aspsine.multithreaddownload.core;

import android.os.Environment;
import android.util.Log;

import com.aspsine.multithreaddownload.CallBack;
import com.aspsine.multithreaddownload.db.DataBaseManager;
import com.aspsine.multithreaddownload.entity.DownloadInfo;
import com.aspsine.multithreaddownload.entity.ThreadInfo;
import com.aspsine.multithreaddownload.util.ListUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Created by Aspsine on 2015/4/20.
 */
public class DownloadRequest implements ConnectTask.OnConnectedListener, DownloadTask.OnDownloadListener {

    private static final int threadNum = 3;

    private final DownloadInfo mDownloadInfo;
    private final File mDownloadDir;
    private final DataBaseManager mDBManager;
    private final ExecutorService mExecutorService;
    private final DownloadStatus mDownloadStatus;
    private final DownloadStatusDelivery mDelivery;

    private List<DownloadTask> mDownloadTasks;

    private boolean mIsPause = false;
    private boolean mCancel = false;


    public DownloadRequest(DownloadInfo downloadInfo, File downloadDir, DataBaseManager dbManager, ExecutorService executorService, DownloadStatus downloadStatus, DownloadStatusDelivery delivery) {
        this.mDownloadInfo = downloadInfo;
        this.mDownloadDir = downloadDir;
        this.mExecutorService = executorService;
        this.mDownloadStatus = downloadStatus;
        this.mDelivery = delivery;
        this.mDBManager = dbManager;
    }

    public void start(CallBack callBack) {
        mIsPause = false;
        mCancel = false;
        mDownloadInfo.setFinished(0);
        mDownloadInfo.setLength(0);
        mDownloadStatus.setCallBack(callBack);
        ConnectTask connectTask = new ConnectTask(mDownloadInfo, this);
        mExecutorService.execute(connectTask);
    }

    public void pause() {
        if (ListUtils.isEmpty(mDownloadTasks)) {
            return;
        }
        mIsPause = true;
        for (DownloadTask task : mDownloadTasks) {
            task.pause();
        }
        mDelivery.postPause(mDownloadStatus);
    }

    public void cancel() {
        if (ListUtils.isEmpty(mDownloadTasks)) {
            return;
        }
        mCancel = true;
        for (DownloadTask task : mDownloadTasks) {
            task.cancel();
        }
        mDBManager.delete(mDownloadInfo.getUrl());
        File file = new File(mDownloadDir, mDownloadInfo.getName());
        if (file.exists() && file.isFile()) {
            file.delete();
        }
        mDelivery.postCancel(mDownloadStatus);
    }

    private boolean isAllFinished() {
        boolean allFinished = true;
        for (DownloadTask downloadTask : mDownloadTasks) {
            if (!downloadTask.isFinished()) {
                allFinished = false;
                break;
            }
        }
        return allFinished;
    }

    private void download(DownloadInfo downloadInfo) {
        List<ThreadInfo> threadInfos = getThreadInfos();
        int finished = 0;
        for (ThreadInfo threadInfo : threadInfos) {
            finished += threadInfo.getFinished();
        }
        mDownloadInfo.setFinished(finished);
        // init tasks
        mDownloadTasks = new ArrayList<>();
        for (ThreadInfo threadInfo : threadInfos) {
            DownloadTask downloadTask = new DownloadTask(threadInfo, downloadInfo, mDownloadDir, mDBManager, this);
            mDownloadTasks.add(downloadTask);
        }
        // start tasks
        for (DownloadTask downloadTask : mDownloadTasks) {
            mExecutorService.execute(downloadTask);
        }
    }

    private List<ThreadInfo> getThreadInfos() {
        // init threadInfo from db
        List<ThreadInfo> threadInfos = mDBManager.getThreadInfos(mDownloadInfo.getUrl());

        if (threadInfos.isEmpty()) {
            // calculate average
            for (int i = 0; i < threadNum; i++) {
                final int average = mDownloadInfo.getLength() / threadNum;
                int end = 0;
                int start = average * i;
                if (i == threadNum - 1) {
                    end = mDownloadInfo.getLength();
                } else {
                    end = start + average - 1;
                }
                Log.i("ThreadInfo", i + ":" + "start=" + start + "; end=" + end);
                ThreadInfo threadInfo = new ThreadInfo(i, mDownloadInfo.getUrl(), start, end, 0);
                threadInfos.add(threadInfo);
            }
        }
        return threadInfos;
    }

    @Override
    public void onConnected(DownloadInfo downloadInfo) {
        mDelivery.postConnected(downloadInfo.getLength(), mDownloadStatus);
        if (!mDownloadDir.exists()) {
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                mDownloadDir.mkdir();
            } else {
                mDelivery.postFailure(new DownloadException("can't make dir!"), mDownloadStatus);
                return;
            }
        }
        if (!(mIsPause || mCancel)) {
            download(downloadInfo);
        }
    }

    @Override
    public void onConnectedFail(DownloadException de) {
        mDelivery.postFailure(de, mDownloadStatus);
    }

    @Override
    public void onProgress(int finished, int length) {
        mDelivery.postProgressUpdate(finished, length, mDownloadStatus);
    }

    @Override
    public void onComplete() {
        if (isAllFinished()) {
            mDBManager.delete(mDownloadInfo.getUrl());
            mDelivery.postComplete(mDownloadStatus);
        }
    }

    @Override
    public void onFail(DownloadException de) {
        mDelivery.postFailure(de, mDownloadStatus);
    }
}
