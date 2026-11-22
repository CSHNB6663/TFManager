package com.rootterm.fm;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;

import java.io.File;

/**
 * 背景音乐播放器。
 *
 * <p>只负责播放「用户自己的音频文件」，<b>不内置任何音乐</b>（避免版权问题）。
 * 默认查找 {@link #DEFAULT_PATH}，也可在设置里选择任意本地音频。</p>
 */
public final class BgmPlayer {

    /** 默认音乐路径（用户自行放入合法音源）。 */
    public static final String DEFAULT_PATH = "/sdcard/Music/发如雪.mp3";

    private static final String PREF = "bgm";

    private static MediaPlayer sPlayer;
    private static String sPlayingPath;
    private static AudioFocusRequest sFocus;
    private static boolean sPausedByFocus;

    private BgmPlayer() {
    }

    // ---------------------------------------------------------------- 偏好

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 是否允许自动播放（默认开）。 */
    public static boolean enabled(Context c) {
        return prefs(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
    }

    /** 当前音乐路径。 */
    public static String path(Context c) {
        String p = prefs(c).getString("path", DEFAULT_PATH);
        return p == null || p.trim().isEmpty() ? DEFAULT_PATH : p.trim();
    }

    public static void setPath(Context c, String p) {
        prefs(c).edit().putString("path", p == null ? "" : p).apply();
    }

    /** 文件是否存在。 */
    public static boolean fileReady(Context c) {
        try {
            return new File(path(c)).isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 播放

    public static boolean isPlaying() {
        try {
            return sPlayer != null && sPlayer.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 开始/继续播放。返回是否成功。 */
    public static boolean play(Context c) {
        String p = path(c);
        File f = new File(p);
        if (!f.isFile()) {
            return false;
        }
        try {
            if (sPlayer != null && p.equals(sPlayingPath)) {
                if (!sPlayer.isPlaying()) {
                    sPlayer.start();
                }
                return true;
            }
            release();
            requestFocus(c);
            sPlayer = new MediaPlayer();
            sPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            sPlayer.setDataSource(p);
            sPlayer.setLooping(true);
            sPlayer.setVolume(0.55f, 0.55f);
            sPlayer.prepare();
            sPlayer.start();
            sPlayingPath = p;
            return true;
        } catch (Throwable t) {
            release();
            return false;
        }
    }

    public static void pause() {
        try {
            if (sPlayer != null && sPlayer.isPlaying()) {
                sPlayer.pause();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            if (sPlayer != null) {
                sPlayer.stop();
            }
        } catch (Throwable ignored) {
        }
        release();
        abandonFocus(c);
    }

    private static void release() {
        try {
            if (sPlayer != null) {
                sPlayer.reset();
                sPlayer.release();
            }
        } catch (Throwable ignored) {
        }
        sPlayer = null;
        sPlayingPath = null;
    }

    // ---------------------------------------------------------------- 音频焦点

    private static AudioManager am(Context c) {
        return (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    private static void requestFocus(Context c) {
        try {
            AudioManager m = am(c);
            if (m == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                sFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                            @Override
                            public void onAudioFocusChange(int change) {
                                switch (change) {
                                    case AudioManager.AUDIOFOCUS_LOSS:
                                        sPausedByFocus = false;
                                        pause();
                                        break;
                                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                                        sPausedByFocus = isPlaying();
                                        pause();
                                        break;
                                    case AudioManager.AUDIOFOCUS_GAIN:
                                        if (sPausedByFocus) {
                                            sPausedByFocus = false;
                                            try {
                                                if (sPlayer != null) {
                                                    sPlayer.start();
                                                }
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                        break;
                                    default:
                                        break;
                                }
                            }
                        })
                        .build();
                m.requestAudioFocus(sFocus);
            } else {
                m.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void abandonFocus(Context c) {
        try {
            AudioManager m = am(c);
            if (m == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                if (sFocus != null) {
                    m.abandonAudioFocusRequest(sFocus);
                    sFocus = null;
                }
            } else {
                m.abandonAudioFocus(null);
            }
        } catch (Throwable ignored) {
        }
    }
}
