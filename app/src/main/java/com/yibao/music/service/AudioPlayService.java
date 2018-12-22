package com.yibao.music.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.Toast;

import com.yibao.music.MusicApplication;
import com.yibao.music.aidl.IMusicAidlInterface;
import com.yibao.music.aidl.MusicBean;
import com.yibao.music.aidl.MusicLyricBean;
import com.yibao.music.manager.MediaSessionManager;
import com.yibao.music.manager.MusicNotifyManager;
import com.yibao.music.model.PlayStatusBean;
import com.yibao.music.model.greendao.MusicBeanDao;
import com.yibao.music.util.Constants;
import com.yibao.music.util.LogUtil;
import com.yibao.music.util.LyricsUtil;
import com.yibao.music.util.QueryMusicFlagListUtil;
import com.yibao.music.util.ReadFavoriteFileUtil;
import com.yibao.music.util.RxBus;
import com.yibao.music.util.SpUtil;

import java.util.List;
import java.util.Random;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;


/**
 * @author Stran
 * Des：${控制音乐的Service}
 * Time:2017/5/30 13:27
 */
public class AudioPlayService
        extends Service {

    private MediaPlayer mediaPlayer;
    private AudioBinder mAudioBinder;
    private static int PLAY_MODE;

    /**
     * 三种播放模式
     */
    public static final int PLAY_MODE_ALL = 0;
    public static final int PLAY_MODE_SINGLE = 1;
    public static final int PLAY_MODE_RANDOM = 2;
    /**
     * 音乐通知栏
     */
    private static final int FAVORITE = 0;
    public static final int PREV = 1;
    public static final int PLAY = 2;
    public static final int NEXT = 3;
    public static final int CLOSE = 4;
    /**
     * 广播匹配
     */
    public final static String BUTTON_ID = "ButtonId";
    public final static String ACTION_MUSIC = "MUSIC";

    private int position = -2;
    private List<MusicBean> mMusicDataList;
    private MusicBroacastReceiver mMusicReceiver;
    private MusicBeanDao mMusicDao;
    private MusicBean mMusicBean;
    private RxBus mBus;
    private Disposable mDisposable;
    private AudioManager mAudioManager;
    private MediaSessionManager mSessionManager;
    private List<MusicLyricBean> mLyricList;

    public void setData(List<MusicBean> list) {
        mMusicDataList = list;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new AudioBinder();
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        super.unbindService(conn);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        init();
        initNotifyBroadcast();
        registerHeadsetReceiver();
        //初始化播放模式
    }

    private void init() {
        mAudioBinder = new AudioBinder();
        mBus = RxBus.getInstance();
        mMusicDao = MusicApplication.getIntstance().getMusicDao();
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mMusicBean = new MusicBean();
        PLAY_MODE = SpUtil.getMusicMode(this);
        mSessionManager = new MediaSessionManager(this, mAudioBinder);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int enterPosition = intent.getIntExtra("position", 0);
        int sortListFlag = intent.getIntExtra("sortFlag", 0);
        int dataFlag = intent.getIntExtra("dataFlag", 0);
        String queryFlag = intent.getStringExtra("queryFlag");
        int sortFlag = sortListFlag == Constants.NUMBER_ZOER ? Constants.NUMBER_ONE : sortListFlag;
        LogUtil.d(" position  ==" + enterPosition + "   sortListFlag  ==" + sortFlag + "  dataFlag== " + dataFlag + "   queryFlag== " + queryFlag);
        mMusicDataList = QueryMusicFlagListUtil.getMusicDataList(mMusicDao, mMusicBean, sortFlag, dataFlag, queryFlag);
        LogUtil.d("    ==  " + mMusicDataList.size());
        if (enterPosition != position && enterPosition != -1) {
            position = enterPosition;
            //执行播放
            try {
                mAudioBinder.play();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        } else if (enterPosition != -1) {
            //通知播放界面更新
            sendCureentMusicInfo();
        }
        MusicBean musicBean = mMusicDataList.get(position);
        musicBean.setPlayFrequency(musicBean.getPlayFrequency() + 1);
        mMusicDao.update(musicBean);
        return START_NOT_STICKY;
    }


    /**
     * 通知播放界面更新
     */
    private void sendCureentMusicInfo() {
        if (position < mMusicDataList.size()) {
            MusicBean musicBean = mMusicDataList.get(position);
            musicBean.setCureetPosition(position);
            mBus.post(musicBean);
        }
    }

    public class AudioBinder
            extends com.yibao.music.aidl.IMusicAidlInterface.Stub
            implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener {
        private MusicBean mMusicInfo;
        private MusicNotifyManager mNotifyManager;

        @Override
        public void play() throws RemoteException {

            if (mediaPlayer != null) {
                mediaPlayer.reset();
                mediaPlayer.release();
                mediaPlayer = null;
            }
            // “>=” 确保模糊搜索时播放不出现索引越界
            position = position >= mMusicDataList.size() ? 0 : position;
            mMusicInfo = mMusicDataList.get(position);
            mediaPlayer = MediaPlayer.create(AudioPlayService.this,
                    Uri.parse(mMusicInfo.getSongUrl()));
            mLyricList = LyricsUtil.getLyricList(mMusicInfo.getTitle(), mMusicInfo.getArtist());
            mediaPlayer.setOnPreparedListener(mp -> {
                // 开启播放
                mediaPlayer.start();
                // 通知播放界面更新
                sendCureentMusicInfo();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                autoPlayNext();
            });
            SpUtil.setMusicPosition(AudioPlayService.this, position);
            showNotifycation(true);
            mSessionManager.updatePlaybackState(true);
            mSessionManager.updateLocMsg();

        }

        @Override
        public List<MusicLyricBean> getLyricList() throws RemoteException {
            return mLyricList;
        }

        @Override
        public void showNotifycation(boolean b) throws RemoteException {
            mNotifyManager = new MusicNotifyManager(getApplication(), mMusicInfo, b);
            mNotifyManager.show();
        }

        @Override
        public void updataFavorite() throws RemoteException {
            MusicBean musicBean = mMusicDataList.get(position);
            boolean favorite = mMusicDao.load(musicBean.getId()).getIsFavorite();
            mNotifyManager.updataFavoriteBtn(favorite);
            new Thread(() -> {
                refreshFavorite(musicBean, favorite);
                // 更新本地收藏文件
                updataFavoritefile(musicBean, favorite);
            }).start();
        }

        @Override
        public void hintNotifycation() throws RemoteException {
            mNotifyManager.hide();
        }

        @Override
        public MusicBean getMusicBean() throws RemoteException {
            return mMusicInfo;
        }
        // 准备完成回调


        // 获取当前播放进度
        @Override
        public int getProgress() throws RemoteException {
            return mediaPlayer.getCurrentPosition();
        }

        // 获取音乐总时长
        @Override
        public int getDuration() throws RemoteException {
            return mediaPlayer.getDuration();
        }


        // 自动播放下一曲
        @Override
        public void autoPlayNext() {
            switch (PLAY_MODE) {
                case PLAY_MODE_ALL:
                    position = (position + 1) % mMusicDataList.size();
                    break;
                case PLAY_MODE_SINGLE:
                    break;
                case PLAY_MODE_RANDOM:
                    position = new Random().nextInt(mMusicDataList.size());
                    break;
                default:
                    break;
            }
            try {
                play();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        // 获取当前的播放模式
        @Override
        public int getPalyMode() throws RemoteException {
            return PLAY_MODE;
        }

        //手动播放上一曲
        @Override
        public void playPre() throws RemoteException {
            switch (PLAY_MODE) {
                case PLAY_MODE_RANDOM:
                    position = new Random().nextInt(mMusicDataList.size());
                    break;
                default:
                    if (position == 0) {
                        position = mMusicDataList.size() - 1;
                    } else {
                        position--;
                    }
                    break;
            }
            play();
        }

        // 手动播放下一曲
        @Override
        public void playNext() throws RemoteException {
            switch (PLAY_MODE) {
                case PLAY_MODE_RANDOM:
                    position = new Random().nextInt(mMusicDataList.size());
                    break;
                default:
                    position = (position + 1) % mMusicDataList.size();
                    break;
            }
            play();
        }

        //true 当前正在播放
        @Override
        public boolean isPlaying() {
            return mediaPlayer.isPlaying();
        }

        @Override
        public void start() throws RemoteException {
            mediaPlayer.start();
            mSessionManager.updatePlaybackState(true);
            showNotifycation(true);
            initAudioFocus();
        }

        //暂停播放
        @Override
        public void pause() throws RemoteException {
            mediaPlayer.pause();
            mSessionManager.updatePlaybackState(false);
            showNotifycation(false);
        }

        @Override
        public void onCompletion(MediaPlayer mediaPlayer) {
            // 自动播放下一首歌曲
            autoPlayNext();
        }

        @Override
        public void onPrepared(MediaPlayer mediaPlayer) {
            // 开启播放
            mediaPlayer.start();
            // 通知播放界面更新
            sendCureentMusicInfo();
        }

        //跳转到指定位置进行播放
        @Override
        public void seekTo(int progress) throws RemoteException {
            mediaPlayer.seekTo(progress);
        }

        @Override
        public void setPalyMode(int playmode) throws RemoteException {
            PLAY_MODE = playmode;
            //保存播放模式

            SpUtil.setMusicMode(AudioPlayService.this, PLAY_MODE);
        }

        @Override
        public List<MusicBean> getMusicList() throws RemoteException {
            return mMusicDataList;
        }

        @Override
        public int getPosition() throws RemoteException {
            return position;
        }
    }

    private void refreshFavorite(MusicBean currentMusicBean, boolean mCurrentIsFavorite) {
        // 数据更新
        currentMusicBean.setIsFavorite(!mCurrentIsFavorite);
        if (!mCurrentIsFavorite) {
            currentMusicBean.setTime(String.valueOf(System.currentTimeMillis()));
        }
        mMusicDao.update(currentMusicBean);
    }

    private void updataFavoritefile(MusicBean musicBean, boolean currentIsFavorite) {
        if (currentIsFavorite) {
            mDisposable = ReadFavoriteFileUtil.deleteFavorite(musicBean.getTitle())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(aBoolean -> {
                        if (!aBoolean) {
                            Toast.makeText(AudioPlayService.this, "该歌曲还没有添加到收藏文件", Toast.LENGTH_SHORT).show();
                        }
                    });
        } else {
            //更新收藏文件  将歌名和收藏时间拼接储存，恢复的时候，歌名和时间以“T”为标记进行截取
            String songInfo = musicBean.getTitle() + "T" + musicBean.getTime();
            ReadFavoriteFileUtil.writeFile(songInfo);

        }

    }

    /**
     * 控制通知栏的广播
     */
    private void initNotifyBroadcast() {
        mMusicReceiver = new MusicBroacastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_MUSIC);
        registerReceiver(mMusicReceiver, filter);

    }

    private class MusicBroacastReceiver
            extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(ACTION_MUSIC)) {
                    int id = intent.getIntExtra(BUTTON_ID, 0);
                    try {
                        switch (id) {
                            case FAVORITE:
                                mAudioBinder.updataFavorite();
                                mBus.post(new PlayStatusBean(1));
                                break;
                            case CLOSE:
                                mAudioBinder.pause();
                                mAudioBinder.hintNotifycation();
                                mBus.post(new PlayStatusBean(2));
                                break;
                            case PREV:
                                mAudioBinder.playPre();
                                break;
                            case PLAY:
                                if (mAudioBinder.isPlaying()) {
                                    mAudioBinder.pause();
                                } else {
                                    mAudioBinder.start();
                                }
                                mBus.post(new PlayStatusBean(0));
                                break;
                            case NEXT:
                                mAudioBinder.playNext();
                                break;
                            default:
                                break;
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 耳机插入和拔出监听广播
     */
    private void registerHeadsetReceiver() {
        IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(headsetReciver, intentFilter);
    }

    BroadcastReceiver headsetReciver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mAudioBinder != null && mAudioBinder.isPlaying()) {
                try {
                    mAudioBinder.pause();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                mBus.post(new PlayStatusBean(0));
            }
        }
    };

    // 音频焦点
    private void initAudioFocus() {
        // 申请焦点
        if (mAudioManager != null) {
            mAudioManager.requestAudioFocus(mAudioFocusChange, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

    }

    private AudioManager.OnAudioFocusChangeListener mAudioFocusChange = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // 长时间丢失焦点,会触发此回调事件，(QQ音乐，网易云音乐)，需要暂停音乐播放，避免和其他音乐同时输出声音
                lossAudioFoucs(true);
                // 若焦点释放掉之后，将不会再自动获得
//                    mAudioManager.abandonAudioFocus(mAudioFocusChange);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // 短暂性丢失焦点，例如播放微博短视频，拨打电话等，暂停音乐播放。
                lossAudioFoucs(true);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 短暂性丢失焦点并作降音处理
                LogUtil.d("====== 33 AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // 当其他应用申请焦点之后又释放焦点会触发此回调,可重新播放音乐
                lossAudioFoucs(false);
                break;
        }
    };

    /**
     * 是否失去焦点
     *
     * @param isLossFoucs b
     */
    private void lossAudioFoucs(boolean isLossFoucs) {
        try {
            if (isLossFoucs) {
                mAudioBinder.pause();
            } else {
                mAudioBinder.start();
            }

        } catch (RemoteException e) {
            e.printStackTrace();
        }
        mBus.post(new PlayStatusBean(0));
    }

    public void abandonAudioFocus() {
        if (mAudioManager != null) {
            mAudioManager.abandonAudioFocus(mAudioFocusChange);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mAudioBinder != null) {
            try {
                mAudioBinder.hintNotifycation();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (mMusicReceiver != null) {
            unregisterReceiver(mMusicReceiver);
        }
        if (headsetReciver != null) {
            unregisterReceiver(headsetReciver);
        }
        if (mDisposable != null) {
            mDisposable.dispose();
            mDisposable = null;
        }
        abandonAudioFocus();
        mSessionManager.release();
        stopSelf();
    }
}
