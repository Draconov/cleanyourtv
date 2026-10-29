package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {
    // ROWA_HOME_REMAP_V2: local Home-only hardware scancode mapping; not global remapping.
    // NO accessibility key filtering; volume, voice, navigation, Browser, power and Home pass through.
    private boolean rowaHomeButton(android.view.KeyEvent event) {
        android.view.InputDevice source = event.getDevice();
        if (source == null || !"MStar Smart TV IR Receiver".equals(source.getName())) return false;
        int scan = event.getScanCode();
        // Log high vendor scancodes to diagnose mismatches between Linux EV_KEY and Android KeyEvent.
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && scan >= 400) {
            android.util.Log.i("ROWA_HOME_REMAP", "scan=" + scan + " keyCode=" + event.getKeyCode());
        }
        String target;
        switch (scan) {
            case 566: target = "com.stremio.one"; break;          // Netflix
            case 752: target = "rezkatv.web.app"; break;          // Prime Video
            case 569: target = "org.smarttube.stable"; break;     // YouTube
            case 456: target = "com.spotify.tv.android"; break;  // TCL Channel
            case 485: target = "org.videolan.vlc"; break;         // Media Player
            default: return false;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) {
            android.content.Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(target);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(target);
            if (launch != null) {
                try {
                    startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    android.util.Log.i("ROWA_HOME_REMAP", "launched " + target + " scan=" + scan);
                } catch (RuntimeException e) {
                    android.util.Log.w("ROWA_HOME_REMAP", "Launch failed for " + target, e);
                }
            } else {
                android.util.Log.w("ROWA_HOME_REMAP", "No launchable activity for " + target);
            }
        }
        return true; // Consume ONLY these five buttons in this Activity.
    }


    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** The system input switcher, forwarded here by InputsRequestActivity while handle_view_inputs is on. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    /** ROWA_V15_13_9: a single scrollable app column, keeping the existing saved dock order. */
    private boolean verticalRail;
    private RowaVerticalRailView verticalRailBackdrop;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private String liveTvPkg;
    /** Launcher settings' change count this Home was built with (see onResume). */
    private long settingsRevision;
    /** Idle time on Home before ClockActivity; 0 is off (config.xml, home_idle_clock_minutes). */
    private long idleClockMs;
    private final Runnable showIdleClock = () -> startActivity(new Intent(this, ClockActivity.class));
    private String liveTvAction;
    private String liveTvInputId;
    /** The app library is on (config.xml, app_library): only then can the tile menu take an app off Home. */
    private boolean appLibraryEnabled;
    /** The same opt-in menu is available from either hold-OK or the remote's Menu key. */
    private boolean tileMenuEnabled;
    private String inputAction;
    private String inputUriScheme;
    private String sourcePanelService;
    private String sourcePanelAction;
    private String sourcePanelExtra;
    private boolean coocaaSources;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private WallpaperPlayer wallpaperPlayer;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** The top shelf: heroName, plus the cinematic shelf's "Last opened" line and dock position dots. */
    private View hero;
    private TextView heroMeta;
    private LinearLayout heroDots;
    /** Cinematic top shelf (config.xml, cinematic_home): the focused dock app's artwork behind a larger shelf. */
    private boolean cinematic;
    /** config.xml ambient_glow, overridable by a theme. Off: the glow view is gone and no fade or colour animator
     *  is started for it, so a focus move costs nothing extra on a launcher that runs all day. */
    private boolean ambientGlow;
    /** config.xml app_glow: the highlight takes the focused app's colour on every tile. */
    private boolean appGlow;
    /** config.xml glow_style: the shape setAmbient paints the glow mask in. */
    private String glowStyle;
    /** config.xml cinematic_white_focus: the cinematic shelf keeps the white highlight. */
    private boolean cinematicWhiteFocus;
    private ImageView heroArt;
    /** config.xml cinematic_dots: false leaves the dock position dots out of the cinematic shelf. */
    private boolean heroDotsShown = true;
    /** The artwork's opacity behind the cinematic shelf (config.xml cinematic_art); 0 leaves it out. */
    private float heroArtAlpha = 0.5f;
    /** Softened artwork per app (HeroArt, about 5 KB each), made the first time its dock tile is focused. */
    private final Map<String, Bitmap> heroArtCache = new HashMap<>();
    /** The app whose artwork heroArt shows, so focusing it again only fades it back in. */
    private String heroArtKey;
    /** The focus glow's colour while the cinematic shelf tints it, 0 for plain white glass. */
    private int glassAccent;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    private View btnInputs;
    /** config show_inputs_pill: false keeps the Inputs pill hidden even when the TV has inputs. */
    private boolean showInputsPill = true;
    private TextView btnWifi;
    /** The network pill's icon as last set, so a refresh that changes nothing does not lay the top bar out again. */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private TextView shamsiDate;
    /** lunar_date: the lunisolar date under the date line, and the time zone it is computed for. */
    private TextView lunarDate;
    private boolean lunar;
    private int lunarOffset;
    private TextView stats;
    // ROWA_WIDGETS_V1: opt-in weather, no extra service or application.
    private TextView rowaWeather;
    private List<RaceWeekend.Race> raceCalendar;
    private View racePanel;
    private TextView raceKicker, raceHeadline, raceSessions;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    /** The same for springX, when single_row scrolls the grid sideways. */
    private float glassScrollShiftX;
    private int glassCardW;   // width of the tile the glass was last fitted to
    private boolean glassShown;
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options в†’ "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    /** config single_row: every app in the dock's row, scrolling sideways, no grid below. */
    private boolean singleRow;
    /** The highlight springs past its target (config.xml, focus_overshoot); false goes straight there. */
    private boolean focusOvershoot;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    private DiyaDrawable diya;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** boot_to_live_tv: open the Live TV app on the first Home of each boot. */
    private boolean bootToLiveTv;
    /** open_on_start_choice: the app picked in Launcher settings may open on the first Home of each boot. */
    private boolean openOnStartChoice;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private boolean settingsRecreationPending;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Between onStop and onStart: Home was fully covered by an app, not just by the translucent quick panel. */
    private boolean stopped;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /** Rebuild the tiles on the way back to Home only when apps changed meanwhile (TileRefresh). */
    private final TileRefresh tileRefresh = new TileRefresh();
    /** Never release Home resources on a quick app switch or a translucent overlay. */
    private static final long BACKGROUND_RELEASE_DELAY_MS = 45_000L;
    private final Runnable releaseHiddenArtwork = () -> {
        if (started || !stopped || isFinishing() || isDestroyed()) return;
        releaseHiddenResources();
    };

    /** PackageManager's change counter at the last rebuild, to catch a package broadcast that never arrived. */
    private int packageSequence;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    private boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    /**
     * fill_dock false: tiles in the dock when reorder mode started, plus those carried or swapped into it since
     * (#499). A grid app that only slid into a free slot, which reorder mode does not show, is not one of them.
     */
    private Set<String> dockInSession;
    /** fill_dock false: how many dock slots held an app when reorder mode started. */
    private int dockedAtStart;
    private View btnReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    /** Tile menu's "Add to dock": the app waiting for a dock place in edit-dock mode, or null. */
    private String dockEditAppKey;
    private AppLibraryDialog appLibrary;
    private boolean appLibrarySelection;
    private boolean libraryOkOpens;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        /** wifi_status_warnings follows validation; signal-strength updates also land here, so skip those. */
        private boolean lastValidated;

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (validated == lastValidated) return;
            lastValidated = validated;
            if (wifiNoInternetTint != null) handler.post(MainActivity.this::updateWifiLabel);
            // Refresh only while this launcher actually owns the visible window.
            // Do not depend on the upstream-only "resumed" field (absent from ROWA v14).
            handler.post(() -> {
                if (!isFinishing() && !isDestroyed() && hasWindowFocus()) {
                    TextView weatherBadge = findViewById(R.id.rowaWeather);
                    if (weatherBadge != null)
                        RowaWeatherSettings.update(MainActivity.this, weatherBadge);
                }
            });
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // In the background this only marks the tiles stale; onResume rebuilds them once.
            if (!tileRefresh.onPackagesChanged()) return;
            reloadApps(false);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateRace();
            RowaWeatherSettings.update(MainActivity.this, rowaWeather);
            // The pill follows the network callbacks and each return to Home; only the diya's flame follows the
            // signal level, so only it needs this minute's binder calls (about 7 ms on the UI thread).
            if (festive) updateWifiLabel();
            sky.update();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        AppAdapter.ROWA_TILE_SCALE = RowaAppearance.scale(this, "cards");
        appLibrarySelection = cfg.appLibrarySelection;
        libraryOkOpens = cfg.libraryOkOpens;
        // restore_key_services: after a force stop, put back the remote's accessibility helpers that Android took
        // off the list (needs WRITE_SECURE_SETTINGS, granted by build-and-install with RESTORE_KEY_SERVICES=1).
        if (cfg.restoreKeyServices) ServiceRepair.restore(this);
        settingsRevision = settingsRevision();
        applyViewInputsHandler(cfg.handleViewInputs);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        COLUMNS = cfg.columns;
        verticalRail = RowaAppearance.get(this, "layout") == 4;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        // Vertical mode takes precedence if an older theme also requested single_row.
        singleRow = cfg.singleRow && !verticalRail;
        focusOvershoot = cfg.focusOvershoot;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        idleClockMs = cfg.homeIdleClockMinutes * MINUTE_MS;
        bootToLiveTv = cfg.bootToLiveTv;
        openOnStartChoice = cfg.openOnStartChoice;
        liveTvAction = cfg.liveTvAction;
        liveTvInputId = cfg.liveTvInputId;
        appLibraryEnabled = cfg.appLibrary;
        tileMenuEnabled = cfg.tileMenu;
        inputAction = cfg.inputAction;
        inputUriScheme = cfg.inputUriScheme;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;
        sourcePanelExtra = cfg.sourcePanelExtra;
        coocaaSources = cfg.coocaaSources;

        root = findViewById(R.id.root);
        RowaV15Perf.recordFirstFrame(root);
        root.setBackgroundColor(cfg.backgroundColor);
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the theme's background, so launch looks the same.
        getWindow().setBackgroundDrawable(null);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        glass.setStyle(cfg.glassStyle);
        glass.setSurfaceOpacity(RowaVisualOptions.panelOpacity(this));
        if (festive) glass.setAccent(festival.accentColor());
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        // The vertical rail supplies its own localized app-color glow. Full-screen
        // ambient tint is unnecessary there and would obscure the wallpaper.
        ambientGlow = cfg.ambientGlow && !verticalRail;
        appGlow = cfg.appGlow;
        if (!ambientGlow) ambient.setVisibility(View.GONE);
        heroName = findViewById(R.id.heroName);
        hero = findViewById(R.id.hero);
        // The hero is now a group (name, and the cinematic lines): without this every fade of the default
        // shelf would render it through an offscreen buffer first, as the glow above notes.
        hero.forceHasOverlappingRendering(false);
        heroMeta = findViewById(R.id.heroMeta);
        heroDots = findViewById(R.id.heroDots);
        heroArt = findViewById(R.id.heroArt);
        cinematic = cfg.cinematicHome;
        glowStyle = cfg.glowStyle;
        heroDotsShown = cfg.cinematicDots;
        cinematicWhiteFocus = cfg.cinematicWhiteFocus;
        heroArtAlpha = verticalRail ? 0f : cfg.cinematicArt / 100f;
        if (cinematic && !verticalRail) setUpCinematicShelf(cfg.cinematicScrim);
        if (verticalRail) {
            heroArt.setVisibility(View.GONE);
            ambient.setVisibility(View.GONE);
            findViewById(R.id.heroScrim).setVisibility(View.GONE);
        }
        // dock_title false: the name above the dock (and the cinematic lines under it) never shows; the glow
        // still follows the dock.
        if (!cfg.dockTitle && !verticalRail) hero.setVisibility(View.GONE);
        greeting = findViewById(R.id.greeting);
        clockBlock = findViewById(R.id.leftBlock);
        greetingColor = greeting.getCurrentTextColor();
        if (!cfg.showGreeting) greeting.setVisibility(View.GONE);
        if (cfg.headerWhite) {
            // The clock's own white for the date and the line before it, instead of their dimmed whites.
            ((TextView) findViewById(R.id.date)).setTextColor(Color.WHITE);
            findViewById(R.id.clockDivider).setBackgroundColor(Color.WHITE);
        }
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            // Center Hebrew greetings over the numeric time, independent of a visible AM/PM label. The clock's row
            // is watched too: date_below_clock can move the whole row right under a wider date.
            View clock = findViewById(R.id.clock);
            View clockRow = (View) clock.getParent();
            View.OnLayoutChangeListener centerGreeting = (v, l, t, right, bottom, ol, ot, or, ob) -> {
                ViewGroup.LayoutParams params = greeting.getLayoutParams();
                if (params.width != clock.getWidth()) {
                    params.width = clock.getWidth();
                    greeting.setLayoutParams(params);
                }
                greeting.setTranslationX(clockRow.getLeft() + clock.getLeft());
            };
            clock.addOnLayoutChangeListener(centerGreeting);
            clockRow.addOnLayoutChangeListener(centerGreeting);
        }
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        stats = findViewById(R.id.stats);
        rowaWeather = findViewById(R.id.rowaWeather);
        raceCalendar = cfg.raceCalendar;
        racePanel = findViewById(R.id.racePanel);
        raceKicker = findViewById(R.id.raceKicker);
        raceHeadline = findViewById(R.id.raceHeadline);
        raceSessions = findViewById(R.id.raceSessions);
        showStats = cfg.showStats;
        shamsi = cfg.shamsiDate;
        lunarDate = findViewById(R.id.lunarDate);
        lunar = cfg.lunarDate;
        lunarOffset = Lunar.offsetFor(cfg.lunarUtcOffset, Locale.getDefault());
        if (lunar) lunarDate.setVisibility(View.VISIBLE);
        greetingName = cfg.greetingName;
        if (cfg.dateBelowClock) moveDateBelowClock();
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            lunarDate.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        // single_row: one span laid out sideways, so the same adapter, glass and dock band serve a scrolling row.
        grid.setLayoutManager(new GridLayoutManager(this, verticalRail || singleRow ? 1 : COLUMNS,
                singleRow ? RecyclerView.HORIZONTAL : RecyclerView.VERTICAL, false) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (verticalRail) {
                    // Keep the complete focused card inside the rail's safe area,
                    // including its focus scale and glow. Padding gives the first
                    // and last cards room to enter fully.
                    int safe = Math.round(52f * density);
                    int top = child.getTop() + rect.top;
                    int bottom = top + rect.height();
                    int dy = top < safe ? top - safe
                            : bottom > getHeight() - safe ? bottom - (getHeight() - safe) : 0;
                    if (dy == 0) return false;
                    if (immediate || reducedMotion || !animations) parent.scrollBy(0, dy);
                    else parent.smoothScrollBy(0, dy);
                    return true;
                }
                if (singleRow) {
                    // Sideways: scroll just enough to bring the tile's cell inside the padding. Cells are a
                    // column wide, so the row moves a whole tile at a time and the tiles stay on the columns.
                    // The whole cell, not the card rect passed in: the card is narrower and centred in it.
                    int left = child.getLeft();
                    int right = child.getRight();
                    int dx;
                    if (left < getPaddingLeft()) dx = left - getPaddingLeft();
                    else if (right > getWidth() - getPaddingRight()) dx = right - (getWidth() - getPaddingRight());
                    else dx = 0;
                    if (dx == 0) return false;
                    if (immediate || reducedMotion || !animations) parent.scrollBy(dx, 0);
                    else parent.smoothScrollBy(dx, 0);
                    return true;
                }
                if (!verticalRail && dockRow && getPosition(child) < COLUMNS) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                // A row already fully on screen stays put, even inside the bottom padding (the last row
                // rows_below_dock leaves visible ends there; nudging it up was the bug). A row that has to scroll
                // in still stops at the padding line, clear of the screen edge, as before.
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < 0) dy = top;
                else if (bottom <= getHeight()) dy = 0;
                else dy = Math.min(top, bottom - limitBottom);
                if (dy == 0) return false;
                if (immediate || reducedMotion || !animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return !verticalRail && !singleRow && adapter != null && adapter.isLibraryAt(position) ? COLUMNS : 1; }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Tiles that scroll out while one is carried come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so none stays dim after the drop.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (verticalRail) {
            // A narrow gap separates the first COLUMNS saved dock positions from the other apps.
            grid.addItemDecoration(new RecyclerView.ItemDecoration() {
                @Override public void getItemOffsets(@NonNull Rect out, @NonNull View child,
                        @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                    if (parent.getChildAdapterPosition(child) == COLUMNS - 1)
                        out.bottom = Math.round(22f * density);
                }
            });
        } else if (dockRow) grid.addItemDecoration(new DockDecoration(COLUMNS, density, cinematic, cfg.alwaysShowNames, cfg.dockOpacity, cfg.dockGlass, cfg.dockEvenPadding, cfg.dockBand,
                singleRow, cfg.dockBandAll));
        // A small GPU-backed fade masks the top and bottom of the vertical rail,
        // avoiding hard-cut tiles while retaining the existing horizontal fade behavior.
        if (verticalRail) RowaVerticalEdgeFade.attach(grid, 42f * density);
        else if (cfg.fadeRows) TopFade.attach(grid, 48 * density);
        // Reduced motion: the tiles are simply there, with no entrance (reloadApps skips it there too).
        if (animations && !reducedMotion) {
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        Order savedOrder = Order.load(Order.file(this));
        adapter.setSavedOrder(Order.ranks(savedOrder, dockApps), Order.docked(savedOrder, dockApps));
        grid.setAdapter(adapter);
        if (verticalRail) configureVerticalRail();

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (verticalRail) {
                grid.post(this::refitGlass);
                return; // No horizontal dock parking or theme grid padding in vertical mode.
            }
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rowsBelowDock, cfg.dockTitle, cfg.dockRow, density);
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Keep the hero below the header when the library is enabled on a short screen,
            // even when the theme requests an extra visible row below the dock.
            int shelfClearance = cfg.appLibrary ? hero.getMeasuredHeight() + (int) (40 * density) : 0;
            int want = Math.max(shelfClearance, h - dockRow - gridRows - (int) (AppAdapter.BOTTOM_ROOM_DP * density));
            // Where the rows cannot fit even at the smallest tile (3 rows with the title on), the title keeps its
            // room above the dock and the last rows run off the bottom instead of the title drawing over the clock.
            if (MainActivity.this.dockRow && cfg.dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            // A single row never shows the names under its tiles (the title above the row names the app), so the
            // 7dp gap and 22dp label item_app.xml keeps for them would only lift the row: sit it that much lower.
            if (singleRow && !cfg.alwaysShowNames) want += (int) (29 * density);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
                // A vertical grid parks the dock on the new padding line by scrolling (onTileFocused); a sideways
                // row cannot scroll vertically, and the layout request made from inside this layout pass is
                // dropped, so lay the row out again on the next frame. The glass and the title were placed for the
                // old position, so fit them again once it has moved.
                if (singleRow) grid.post(() -> {
                    grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
                            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
                            View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
                            if (card != null && card.isFocused()) onTileFocused(pos, card);
                        }
                    });
                    grid.requestLayout();
                });
            }
            // Cold start or column count > 4: the first focus can run before the grid has its final width or
            // padding, leaving the glass at the XML design size until a key is pressed.
            grid.post(this::refitGlass);
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // Keep the colored rail spotlight under the moving focused card.
                if (verticalRail && verticalRailBackdrop != null && adapter != null) {
                    RecyclerView.ViewHolder focused = grid.findViewHolderForAdapterPosition(lastFocused);
                    View card = focused == null ? null : focused.itemView.findViewById(R.id.card);
                    if (card != null && card.isFocused()) {
                        Rect rect = new Rect(0, 0, card.getWidth(), card.getHeight());
                        root.offsetDescendantRectToMyCoords(card, rect);
                        verticalRailBackdrop.setFocusedTile(rect.exactCenterY(),
                                adapter.ambientColorAt(lastFocused));
                    }
                }
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
                // single_row scrolls sideways: the same bookkeeping on the other axis.
                if (dx != 0) {
                    glassTargetX -= dx;
                    glassScrollShiftX -= dx;
                    glass.setTranslationX(glass.getTranslationX() - dx);
                }
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentX") {
            @Override
            public float getValue(View v) {
                return v.getTranslationX() - glassScrollShiftX;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationX(value + glassScrollShiftX);
            }
        });
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                Motion.reduce(s);
            } else if (!focusOvershoot) {
                // Critically damped: glides straight onto the tile without swinging past it.
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 440f).setDampingRatio(1f));
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                        .setDampingRatio(advancedEffects ? 0.76f : 0.58f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        launcherSettingsButton = cfg.launcherSettingsButton;
        if (launcherSettingsButton) {
            ((TextView) btnSettings).setText(R.string.launcher_settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_home_settings_pill, 0, 0, 0);
        }
        btnSettings.setOnClickListener(v -> {
            if (launcherSettingsButton) startActivityForResult(
                    new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
            else open(systemIntent(Settings.ACTION_SETTINGS));
        });
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(cfg.reorderButton ? View.VISIBLE : View.GONE);
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        showInputsPill = cfg.showInputsPill;
        showInputsMenuRow = cfg.showInputsPill;
        btnLibraryTop = findViewById(R.id.btnLibraryTop);
        btnLibraryTop.setOnClickListener(v -> {
            onLibraryRequested();
            libraryFromTopBar = true;   // closing it goes back to this button, not to the pill after the last row
        });
        btnLibraryTop.setVisibility(cfg.libraryButton && cfg.appLibrary ? View.VISIBLE : View.GONE);
        addTopBarApps(cfg.topBarApps);
        iconOnlyButtons = cfg.iconOnlyButtons;
        topMenu = cfg.topMenu;
        wifiIconOnly = iconOnlyButtons && cfg.wifiLabelWidth <= 0;
        if (cfg.wifiStatusColor) {
            wifiPlainTint = btnWifi.getCompoundDrawableTintList();
            wifiOnlineTint = getColorStateList(R.color.pill_icon_online);
            if (cfg.wifiStatusWarnings) {
                wifiNoInternetTint = getColorStateList(R.color.pill_icon_no_internet);
                wifiOfflineTint = getColorStateList(R.color.pill_icon_offline);
            }
        }
        if (cfg.topMenu) {
            // One gear: Inputs and Wi-Fi move into its dropdown. The Wi-Fi pill stays hidden but keeps its
            // label up to date, which the menu's Wi-Fi row shows.
            showInputsPill = false;
            btnInputs.setVisibility(View.GONE);
            btnWifi.setVisibility(View.GONE);
            ((TextView) btnSettings).setText(R.string.settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0);
            if (!iconOnlyButtons) makeIconOnly((TextView) btnSettings);
            btnSettings.setOnClickListener(v -> showTopMenu());
            // The lone gear sits at the header's middle, not on its bottom line like the row of pills.
            LinearLayout topButtons = findViewById(R.id.topButtons);
            RelativeLayout.LayoutParams tlp = (RelativeLayout.LayoutParams) topButtons.getLayoutParams();
            tlp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
            tlp.bottomMargin = 0;
            topButtons.setLayoutParams(tlp);
            topButtons.setGravity(Gravity.CENTER_VERTICAL);
        }
        if (iconOnlyButtons) {
            if (cfg.wifiLabelWidth > 0) btnWifi.setMaxWidth(Math.round(cfg.wifiLabelWidth * density));
            else makeIconOnly(btnWifi);
            makeIconOnly((TextView) btnSettings);
        }
        if (cfg.iconOnlyInputs) makeIconOnly((TextView) btnInputs);
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });
        rowaSetupHeader();
        RowaAppearance.applyHeader(this);
        if (verticalRail) configureVerticalHeader();

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (verticalRail && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (v == firstVerticalTopButton()) { focusTile(lastFocused); return true; }
                return false; // Move between the other visible top buttons normally.
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the exact vertical rail tile, or the original dock position.
                focusTile(verticalRail || singleRow ? lastFocused : lastFocused % COLUMNS);
                return true;
            }
            int firstButton = btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : firstTopButton().getId();
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnReorder.setOnKeyListener(pillKeys);
        btnLibraryTop.setOnKeyListener(pillKeys);
        // Stats can be relocated into the top row by rowaSetupHeader(). Include it in
        // rail-to-pill focus navigation, otherwise LEFT on the first Stats pill escapes.
        if (verticalRail && stats.getParent() == findViewById(R.id.topButtons))
            stats.setOnKeyListener(pillKeys);
        for (View button : topAppButtons) button.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            List<View> pills = new ArrayList<>(Arrays.asList(btnInputs, btnWifi, btnReorder, btnLibraryTop, btnSettings));
            pills.addAll(topAppButtons);
            for (View button : pills) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(reducedMotion ? Motion.REDUCED_MS : 160).start());
            }
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        // Registered for the activity's whole life, not only while it is started: an app installed or updated while
        // another app is in front must still mark the tiles stale, or Home would have to rebuild on every return.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null && !openLauncherSettingsIfAsked(getIntent())) openInputsIfAsked(getIntent());
    }

    /**
     * handle_view_inputs: switch InputsRequestActivity, the VIEW_INPUTS handler, on or off. It is disabled in the
     * manifest, so a TV with its own input picker never sees it; only the sets that need it turn it on. Written
     * only when the state differs, so an ordinary start does not touch the package manager.
     */
    private void applyViewInputsHandler(boolean on) {
        ComponentName handler = new ComponentName(this, InputsRequestActivity.class);
        PackageManager pm = getPackageManager();
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (pm.getComponentEnabledSetting(handler) != want) {
            pm.setComponentEnabledSetting(handler, want, PackageManager.DONT_KILL_APP);
        }
    }

    /**
     * VIEW_INPUTS, forwarded by InputsRequestActivity while handle_view_inputs is on: open the Inputs menu once
     * the pill is on screen. The intent is replaced so a later recreate() does not
     * open it again. Nothing to do when the TV has no inputs to list (the pill is hidden then).
     * top_menu hides the pill, so the list hangs under the gear there, as in showInputs().
     */
    private boolean openInputsIfAsked(Intent intent) {
        if (intent == null || !ACTION_VIEW_INPUTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        View anchor = topMenu ? findViewById(R.id.btnSettings) : btnInputs;
        if (anchor.getVisibility() != View.VISIBLE) return true;
        if (anchor.isLaidOut()) {
            anchor.requestFocus();
            showInputs();
            return true;
        }
        // Cold start: the popup anchors to the pill (or the gear), so wait for the first layout.
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                anchor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                anchor.post(() -> {
                    if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                    anchor.requestFocus();
                    showInputs();
                });
            }
        });
        return true;
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (openInputsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        wallpaperPlayer.onHomePressed();
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        if (stopped) {
            tileRefresh.onHomeAgain();
        } else {
            // Still on screen (the quick panel was over it, or Home was already in front): the tiles are current,
            // so only go back to the first one; rebuilding would replay the entrance animation for nothing.
            grid.post(() -> focusTile(0));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(releaseHiddenArtwork);
        stopped = false;
        started = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
        tileRefresh.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        // Something changed in Launcher settings: rebuild with it, however the settings were left. Only Back
        // delivered their RESULT_OK; Home (the remote's button) clears them off this task and the result comes
        // back cancelled, so a new wallpaper or layout waited for the next process start.
        if (Config.settingsChanged(settingsRevision, settingsRevision())) {
            recreate();
            return;
        }
        restartIdleClock();
        promptsPaused = false;
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // dropped by onTrimMemory
        switch (tileRefresh.onResume(packagesChangedSinceRebuild())) {
            case REBUILD:
                tilesReady = false;
                reloadApps(true);
                break;
            case REFOCUS:
                // The same tiles: only the focus goes back, as after a rebuild, without replaying the entrance.
                tilesReady = false;
                reloadApps(true, false, false);
                break;
            default:
                break;
        }
        if (openLiveTvAfterBoot()) return;
        updateGreeting();
        updateRace();
        // The network and Inputs pills keep their state while Home is in the background, and callbacks update them
        // when the network or the inputs change, so this refresh only catches anything missed. Its binder calls
        // (about 7 ms on a TCL C755, most of it WifiManager.getConnectionInfo) would hold up the first frame back
        // on Home, so after the first resume it runs once that frame is drawn.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        if (showStats) startStats();
        // ROWA_APPEARANCE_V6: layout changes rebuild Home, then resume this exact menu row.
        String restoreRowaMenu = rowaAppearanceReopenKey;
        if (restoreRowaMenu != null) {
            rowaAppearanceReopenKey = null;
            root.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) rowaShowAppearanceMenu(restoreRowaMenu);
            }, 130);
        }
        RowaWeatherSettings.update(this, rowaWeather);
        sky.update();
        wallpaperPlayer.start();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && diya != null) {
            diya.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
    }

    /** Launcher settings' change count (Config.SETTINGS_REVISION_KEY); onResume rebuilds Home when it moved. */
    private long settingsRevision() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getLong(Config.SETTINGS_REVISION_KEY, 0);
    }

    /**
     * Only "Reorder tiles" in Launcher settings still answers with a result: Home rebuilds (the saved choices are
     * reloaded) and then enters reorder mode. Every other change is picked up in onResume through the change count.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ROWA_V15_WALLPAPER_PICK) {
            if (resultCode == RESULT_OK && data != null)
                RowaVisualOptions.replaceSelection(this, data, imported -> {
                    Toast.makeText(this, imported > 0 ?
                            getString(R.string.rowa_v15_wallpaper_success, imported) :
                            getString(R.string.rowa_v15_wallpaper_error), Toast.LENGTH_LONG).show();
                    if (imported > 0) recreate();
                });
            return;
        }
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK
                && data != null && data.getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) {
            getIntent().putExtra(LauncherSettingsActivity.EXTRA_REORDER, true);
            // The old activity must not consume the pending action, or rebuild a second time in onResume.
            settingsRecreationPending = true;
            settingsRevision = settingsRevision();
            recreate();
        }
    }

    private void startPendingSettingsReorder() {
        if (settingsRecreationPending || !tilesReady || !hasWindowFocus() || isFinishing()
                || !getIntent().getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) return;
        getIntent().removeExtra(LauncherSettingsActivity.EXTRA_REORDER);
        toggleReorder();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        reloadApps(animate, true);
    }

    /** @param rebuild false keeps the tiles as they are and only puts focus back (TileRefresh.Action.REFOCUS). */
    private void reloadApps(boolean animate, boolean rebuild) {
        reloadApps(animate, rebuild, true);
    }

    /**
     * @param entrance false puts focus back as an animated reload would, without replaying the tiles' entrance:
     *                 on a return to the same tiles it would only mark a change that didn't happen.
     */
    private void reloadApps(boolean animate, boolean rebuild, boolean entrance) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.animate().cancel();
            heroArt.setAlpha(0f);
        }
        if (rebuild) reloadTiles();
        if (animate && entrance && animations && !reducedMotion) {
            grid.scheduleLayoutAnimation();
            // A rebuild redraws the grid anyway; without one the entrance needs a draw to start.
            if (!rebuild) grid.invalidate();
        }

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            startPendingSettingsReorder();
            showCardPrompt();
        }
    }

    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */
    private boolean packagesChangedSinceRebuild() {
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        return changed != null && !changed.getPackageNames().isEmpty();
    }

    private int currentPackageSequence(int since) {
        ChangedPackages changed = getPackageManager().getChangedPackages(since);
        return changed != null ? changed.getSequenceNumber() : since;
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // An updated app may bring new artwork; the backdrop is cheap to make again.
        heroArtCache.clear();
        heroArtKey = null;
        // One LEANBACK query for both the tiles and the card checks.
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps,
                () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), leanback);
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        packageSequence = currentPackageSequence(packageSequence);
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            startPendingSettingsReorder();
            showCardPrompt();
        });
    }

    /** Any key on Home restarts the countdown to the idle clock. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdleClock();
    }

    private void restartIdleClock() {
        root.removeCallbacks(showIdleClock);
        if (idleClockMs > 0) root.postDelayed(showIdleClock, idleClockMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        root.removeCallbacks(showIdleClock);
        endReorder();   // Home or another app while reordering keeps the order so far
        wallpaperPlayer.stop();
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        handler.removeCallbacks(minuteTick);
        stopStats();
        if (diya != null) diya.stop();
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        unregisterReceiver(timeReceiver);
        handler.removeCallbacks(clockCheck);
        stopped = true;
        tileRefresh.onStop();
        handler.removeCallbacks(releaseHiddenArtwork);
        handler.postDelayed(releaseHiddenArtwork, BACKGROUND_RELEASE_DELAY_MS);
    }

    /** Remove heavy Home resources only while truly hidden; the next resume rebuilds tiles and wallpaper. */
    private void releaseHiddenResources() {
        if (started || !stopped || grid.getAdapter() == null) return;
        heroArt.animate().cancel();
        heroArt.setImageDrawable(null); // releasing the cache alone leaves a bitmap held by this view
        heroArtCache.clear();
        heroArtKey = null;
        adapter.clear();
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        AppAppearance.trimArtworkCache();
        wallpaperPlayer.releaseForMemoryPressure();
        sky.releaseTexture();
        tileRefresh.invalidate();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the
        // process in the foreground; never blank the screen the owner is looking at.
        if (!MemoryTrimPolicy.shouldRelease(level, started)) return;
        handler.removeCallbacks(releaseHiddenArtwork);
        releaseHiddenResources();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            startPendingSettingsReorder();
            if (libraryFocusPending && appLibrary == null) focusLibrary();
            showCardPrompt();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(releaseHiddenArtwork);
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        wallpaperPlayer.close();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : systemIntent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    /**
     * Reorder mode (top-bar button). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (rowaHomeButton(event)) return true;
        if (handleSettingsKey(event)) return true;
        if (tileMenuEnabled && !reordering && openPopup == null && cardPrompt == null
                && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            int position = focusedTilePosition();
            if (position >= 0 && position < adapter.appCount()
                    && !getPackageName().equals(adapter.packageAt(position))) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    onTileLongPress(position, getCurrentFocus());
                }
                return true;
            }
        }
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) drop();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        View focus = getCurrentFocus();
        if (ok && focus != null && focus.getId() == R.id.btnLibrary) {
            // The App library pill sits in the grid but is no tile: OK ends the mode (saving the order) and opens it.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                endReorder();
                onLibraryRequested();
            }
            return true;
        }
        if (ok && grid.hasFocus()) {
            // RecyclerView can briefly report NO_POSITION during a move or rebind. Still consume
            // OK on the grid: falling through would let the focused card open its app in reorder mode.
            if (!down && pos >= 0) pickUp(pos);
            return true;
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (!beginReorder()) return;
        focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        if (adapter.movableCount() < 2) return false;
        // Before the reload below: once it has dropped the empty slots, the dock row also shows grid apps.
        dockInSession = new HashSet<>();
        dockedAtStart = adapter.dockFilled();
        for (int i = 0; i < Math.min(dockedAtStart, adapter.movableCount()); i++) dockInSession.add(adapter.keyAt(i));
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        orderAtStart = adapter.movableKeys();
        glass.setAccent(REORDER_ACCENT);
        btnReorder.setActivated(true);
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditAppKey != null) {
                    endReorder();   // "Add to dock" cancelled before a place was chosen
                } else if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditAppKey != null) {
                    if (pos < Math.min(COLUMNS, n)) {
                        int from = adapter.positionOf(dockEditAppKey);
                        if (from >= 0) swapIntoDock(pos, from);
                        else endReorder();
                    }
                } else if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /**
     * Edit-dock mode started from the tile menu. A dock app: its place is already chosen, so focus goes to the grid
     * for the app to put there. A grid app: it is already chosen, so focus goes to the dock for the place.
     */
    private void startDockEditFor(String key, boolean inDock) {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        int pos = adapter.positionOf(key);   // beginReorder can rebuild the tiles
        int n = adapter.movableCount();
        if (pos < 0) {
            endReorder();
        } else if (inDock) {
            dockEditSlot = pos;
            dimOthers();
            focusTile(Math.min(COLUMNS, n - 1));
            Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
        } else {
            dockEditAppKey = key;
            focusTile(0);
            Toast.makeText(this, R.string.dock_edit_pick_place, Toast.LENGTH_LONG).show();
        }
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
            dockInSession.add(adapter.keyAt(slot));
            if (pos >= COLUMNS) dockInSession.remove(slotKey);
        }
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        dimOthers();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        dimOthers();
    }

    /** While a tile is carried, every other tile is dimmed so the carried one stands out; otherwise all are full. */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** A tile's alpha: dim unless it is the carried one, or, in edit-dock mode, a dock tile other than the chosen place. */
    private float tileAlpha(int pos) {
        if (movingKey != null) {
            return pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
        }
        if (dockEditSlot >= 0 && pos >= 0 && pos < COLUMNS && pos != dockEditSlot) return 0.45f;
        return 1f;
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        dockEditing = false;
        dockEditSlot = -1;
        dockEditAppKey = null;
        drop();
        reordering = false;
        adapter.setReordering(false);
        glass.setAccent(festive ? festival.accentColor() : 0);
        btnReorder.setActivated(false);
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        // fill_dock false: the dock is what the owner put there, saved with its size, so the empty slots that
        // come back after the mode go after it (#499). With fill_dock true every dock slot holds an app anyway.
        int docked = -1;
        if (fillDockLeavesSlots) {
            docked = DockSlots.dockCount(keys, COLUMNS, dockInSession);
            keys = DockSlots.dockFirst(keys, COLUMNS, dockInSession);
        }
        dockInSession = null;
        if (!keys.equals(before) || (fillDockLeavesSlots && docked != dockedAtStart)) {
            Order order = new Order(Order.dockKey(dockApps), keys, docked);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps), Order.docked(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        // A single row has no rows to move between: Up and Down leave the carried tile where it is.
        if (singleRow && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) return;
        int to;
        if (verticalRail) {
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return;
            to = from + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (to < 0 || to >= n) return;
            keepScroll(() -> adapter.move(from, to));
            if (to < COLUMNS) dockInSession.add(movingKey);
            else dockInSession.remove(movingKey);
            refocusAfterLayout(to);
            return;
        }
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        // The owner carries this tile: where it lands decides whether it is in the dock, not dock_apps (#499).
        if (to < COLUMNS) dockInSession.add(movingKey);
        else dockInSession.remove(movingKey);
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        // single_row scrolls sideways, so its first visible tile is pinned by its left edge instead; mirrored by
        // rtl_app_grid, the row is laid out from the right, and the offset counts from the right edge.
        int offset = firstView == null ? 0
                : singleRow && rtlAppGrid ? grid.getWidth() - grid.getPaddingRight() - lm.getDecoratedRight(firstView)
                : singleRow ? lm.getDecoratedLeft(firstView) - grid.getPaddingLeft()
                : lm.getDecoratedTop(firstView) - grid.getPaddingTop();
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    @Override
    public void onBackPressed() {
        // ROWA_V13: fallback when a vendor redirects Back to the Activity.
        if (openPopup != null && openPopup.isShowing()) {
            openPopup.dismiss();
            return;
        }
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onLibraryRequested() {
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        libraryFocusPending = false;
        libraryFromTopBar = false;
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, appLibrarySelection, libraryOkOpens, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            if (libraryFromTopBar) {
                libraryFromTopBar = false;
                btnLibraryTop.requestFocus();
                return;
            }
            libraryFocusPending = true;
            grid.post(() -> {
                if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
            });
        });
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Focus restored before the first layout (cold start): the tile has no size yet, and the glass
        // would be sized to 2*pad. Run again once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h || card.getWidth() != glassCardW) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassCardW = card.getWidth();
        glassTargetX = x;
        glassTargetY = y;
        // Cinematic shelf: the highlight glows in the dock app's colour, plain white in the grid (and on the dock with
        // cinematic_white_focus). app_glow: every tile. In a single row every tile is the dock's kind.
        boolean coloured = (verticalRail || appGlow || cinematic && !cinematicWhiteFocus
                && (singleRow || position < COLUMNS)) && !festive;
        int accent = coloured ? adapter.ambientColorAt(position) : 0;
        if ((verticalRail || cinematic || appGlow) && !festive && accent != glassAccent) {
            glassAccent = accent;
            glass.setAccent(accent);
        }
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations && !reducedMotion) glass.animate().alpha(1f).setDuration(180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x - glassScrollShiftX);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (verticalRail) {
            // A localized, app-colored spotlight sits under the selected tile;
            // the rest of the wallpaper is never tinted by a full-screen glow.
            if (verticalRailBackdrop != null)
                verticalRailBackdrop.setFocusedTile(r.exactCenterY(), adapter.ambientColorAt(position));
            // All apps, including the dock section, are represented by one name in the open space.
            showHero(position, r.top);
            revealRect.set(0, -(int) (pad * 2), item.getWidth(), item.getHeight() + (int) pad * 2);
            grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
        } else if (dockRow && (singleRow || position < COLUMNS)) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app. In a single row
            // every tile is in the dock's row, which never scrolls vertically.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = !singleRow && Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            // Below the dock (or in a plain grid) the glow follows the tile without the big name; letting it
            // fade out left the grid on plain black.
            showGlowOnly(position);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            revealRect.set(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
        }
    }

    /** Refits the glass to the focused holder once the grid layout and tile dimensions settle. */
    private void refitGlass() {
        if (isDestroyed() || adapter.appCount() == 0 || libraryFocusPending) return;
        int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() <= 0 || !card.isFocused()) return;
        float pad = 6f * density;
        float scale = adapter.focusScale();
        int w = (int) (card.getWidth() * scale + 2 * pad);
        int h = (int) (card.getHeight() * scale + 2 * pad);
        if (!glassShown || card.getWidth() != glassCardW || glass.getWidth() != w || glass.getHeight() != h) {
            onTileFocused(pos, card);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = verticalRail
                ? Math.max(180f * density, (root.getHeight() - hero.getHeight()) * 0.55f)
                : dockTopInRoot - 14 * density - 26 * density - hero.getHeight();
        // Vertical title positioning is set once in configureVerticalRail using
        // layout margins. Keep focus animations free to adjust translationX.
        boolean changed = !label.contentEquals(heroName.getText());
        if (cinematic && heroArtAlpha > 0f) showHeroArt(position);

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.setAlpha(1f);
            if (ambientGlow) {
                setAmbient(color);
                ambient.setAlpha(1f);
            }
            heroShown = true;
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.animate().alpha(1f).setDuration(260).start();
            if (ambientGlow) {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(600).start();
            }
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) hero.animate().cancel();
            hero.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                fillHero(position, label);
                hero.setY(y);
                hero.setTranslationX(12 * density);
                hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (ambientGlow && advancedEffects) {
                transitionAmbient(color);
            } else if (ambientGlow && color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            hero.setY(y);
        }
    }

    /** The shelf's text for this dock app: its name and, on the cinematic shelf, "Last opened" and the dots. */
    private void fillHero(int position, String label) {
        heroName.setText(label);
        if (!cinematic || verticalRail) return;
        updateHeroMeta(position);
        updateHeroDots(position);
    }

    // ---- cinematic shelf -------------------------------------------------------------------------

    /**
     * The parts that never change: a larger name, the meta line and the dots, the artwork and the scrim, at
     * scrimPercent of its strength (cinematic_scrim). cinematic_art 0 leaves the artwork out, 0 percent the scrim.
     */
    private void setUpCinematicShelf(int scrimPercent) {
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f);
        heroName.setLetterSpacing(-0.025f);
        heroMeta.setVisibility(View.INVISIBLE);   // keeps its height, so the name does not jump when it fills in
        heroDots.setVisibility(heroDotsShown ? View.VISIBLE : View.GONE);
        heroArt.setVisibility(heroArtAlpha > 0f ? View.VISIBLE : View.GONE);
        // One stretched bitmap: apply its alpha while drawing instead of through an offscreen buffer.
        heroArt.forceHasOverlappingRendering(false);
        if (scrimPercent <= 0) return;   // cinematic_scrim 0: no scrim at all
        View scrim = findViewById(R.id.heroScrim);
        Drawable shade = scrimDrawable();
        // On the bitmap's paint, not the view: a view's alpha would draw the full screen through an offscreen buffer.
        shade.setAlpha(scrimPercent * 255 / 100);
        scrim.setBackground(shade);
        scrim.setVisibility(View.VISIBLE);
    }

    /**
     * Darkens the side the shelf's text is on and the lower part of the screen, so the name, the dock and
     * the grid stay readable over the artwork. One small bitmap stretched over the screen, like the glow.
     */
    private Drawable scrimDrawable() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(rtl ? w : 0, 0, rtl ? 0 : w, 0,
                new int[]{0xD1060406, 0x59060406, 0x00060406}, new float[]{0f, 0.42f, 0.7f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00060406, 0x00060406, 0xDB060406, 0xFF060406}, new float[]{0f, 0.28f, 0.58f, 0.76f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable scrim = new BitmapDrawable(getResources(), bmp);
        scrim.setGravity(Gravity.FILL);
        return scrim;
    }

    /** Swaps the backdrop to this app's artwork: fade out, swap, fade in, the way the shelf's name changes. */
    private void showHeroArt(int position) {
        String key = adapter.keyAt(position);
        if (key == null) return;
        heroArt.animate().cancel();
        if (key.equals(heroArtKey)) {
            fadeHeroArt(420);
            return;
        }
        Bitmap art = heroArtCache.get(key);
        if (art == null) {
            art = HeroArt.from(adapter.artworkAt(position));
            if (art != null) heroArtCache.put(key, art);
        }
        heroArtKey = key;
        final Bitmap next = art;
        if (!animations || reducedMotion || heroArt.getAlpha() == 0f) {
            heroArt.setImageBitmap(next);
            fadeHeroArt(600);
            return;
        }
        heroArt.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            heroArt.setImageBitmap(next);
            heroArt.animate().alpha(heroArtAlpha).setDuration(420).start();
        }).start();
    }

    private void fadeHeroArt(int ms) {
        if (!animations) heroArt.setAlpha(heroArtAlpha);
        else heroArt.animate().alpha(heroArtAlpha).setDuration(reducedMotion ? Math.min(ms, Motion.REDUCED_MS) : ms).start();
    }

    /** "Last opened yesterday, 9:40 PM", the time in white; nothing (but the same height) when it was never seen. */
    private void updateHeroMeta(int position) {
        long when = LastOpened.get(this, adapter.packageAt(position));
        if (when <= 0) {
            heroMeta.setVisibility(View.INVISIBLE);
            return;
        }
        String rel = LastOpened.describe(this, when);
        String text = getString(R.string.last_opened, rel);
        SpannableString line = new SpannableString(text);
        int at = text.indexOf(rel);
        if (at >= 0) line.setSpan(new ForegroundColorSpan(0xFFFFFFFF), at, at + rel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        heroMeta.setText(line);
        heroMeta.setVisibility(View.VISIBLE);
    }

    /** One dot per dock app, the focused one drawn long and white. Hebrew docks start on the right, so do the dots. */
    private void updateHeroDots(int position) {
        if (!heroDotsShown) return;
        int count = Math.min(adapter.dockFilled(), COLUMNS);
        heroDots.setVisibility(count > 1 ? View.VISIBLE : View.INVISIBLE);
        if (heroDots.getChildCount() != count) {
            heroDots.removeAllViews();
            for (int i = 0; i < count; i++) {
                View dot = new View(this);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(3 * density);
                dot.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (6 * density), (int) (6 * density));
                if (i > 0) lp.leftMargin = (int) (6 * density);
                heroDots.addView(dot, lp);
            }
        }
        for (int i = 0; i < count; i++) {
            View dot = heroDots.getChildAt(rtlAppGrid ? count - 1 - i : i);
            boolean on = i == position;
            ((GradientDrawable) dot.getBackground()).setColor(on ? 0xFFFFFFFF : 0x4DFFFFFF);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            int w = (int) ((on ? 20 : 6) * density);
            if (lp.width != w) {
                lp.width = w;
                dot.setLayoutParams(lp);
            }
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920Г—1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (cinematic) {
                // Higher and stronger, from the corner away from the shelf's text: the app's colour floods
                // the top of the screen over its artwork, and the scrim keeps the text side dark.
                boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
                paint.setShader(new RadialGradient(
                        w * (rtl ? 0.20f : 0.80f), h * 0.06f, w * 0.78f,
                        new int[]{0xA6FFFFFF, 0x47FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
            } else if (Config.GLOW_HORIZON.equals(glowStyle)) {
                // The colour washes down from the top edge and fades out three quarters of the way down,
                // with a brighter band along the top like light over a horizon.
                paint.setShader(new LinearGradient(0, 0, 0, h * 0.75f,
                        new int[]{0x66FFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.4f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(new RadialGradient(
                        w * 0.5f, -h * 0.25f, w * 0.55f,
                        new int[]{0x40FFFFFF, 0x00FFFFFF},
                        null,
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(new RadialGradient(
                        w * 0.32f, h * 0.42f, w * 0.62f,
                        new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    /**
     * Grid tiles (every tile with dock_row false): the glow behind the grid takes the focused tile's colour; the big
     * name above the dock does not show (every grid tile has its name underneath).
     */
    private void showGlowOnly(int position) {
        int color = adapter.ambientColorAt(position);
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        hero.setAlpha(0f);
        heroArt.animate().cancel();
        heroArt.setAlpha(0f);
        heroShown = false;
        if (!ambientGlow) return;
        if (!animations || reducedMotion) {
            setAmbient(color);
            ambient.setAlpha(1f);
            return;
        }
        if (color == ambientColor && ambient.getBackground() != null) {
            ambient.animate().alpha(1f).setDuration(300).start();
            return;
        }
        // The same dip-and-swap as the dock's top shelf.
        ambient.animate().alpha(0.35f).setDuration(ambient.getAlpha() > 0.35f ? 200 : 0).withEndAction(() -> {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(500).start();
        }).start();
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        heroShown = false;
        if (animations && !reducedMotion) {
            hero.animate().alpha(0f).setDuration(160).start();
            if (ambientGlow) ambient.animate().alpha(0f).setDuration(300).start();
            heroArt.animate().alpha(0f).setDuration(300).start();
        } else {
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations && !reducedMotion) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(rowaColorCpuTemperature(line));
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        // Paused (the quick panel or an app on top): keep the last line on screen. Taking it away moved the grid up
        // under a glass highlight that stayed put.
        if (!showStats) stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  В·  Memory 430 MB free  В·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  В·  ");
        line.append(part);
    }

    // ROWA_APPEARANCE_V6: CPU temperature, not CPU usage. Only the numeric temperature goes red.
    private CharSequence rowaColorCpuTemperature(String line) {
        int start = line.indexOf("CPU ");
        int end = line.indexOf(" | RAM ");
        if (start < 0 || end < 0) return line;
        String value = line.substring(start + 4, end);
        if (!value.endsWith("\u00b0C")) return line;
        try {
            int degrees = Integer.parseInt(value.substring(0, value.length() - 2));
            if (!RowaCpuThermal.isHot(degrees)) return line;
            android.text.SpannableString result = new android.text.SpannableString(line);
            result.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF4D59),
                    start + 4, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return result;
        } catch (NumberFormatException ignored) {
            return line;
        }
    }

    /** This is called on the existing 10-second background stats thread, not on the UI thread. */
    private String readStatsLine() {
        StringBuilder line = new StringBuilder("CPU ");
        int temperature = RowaCpuThermal.readCelsius();
        if (temperature >= 0) line.append(temperature).append("\u00b0C");
        else line.append("--"); // The OS hid the CPU sensor; do not guess.
        line.append(" | RAM ");
        long[] mem = memUsedOfTotalMb();
        if (mem == null || mem[1] <= 0) line.append("--");
        else {
            int percent = (int) Math.round(mem[0] * 100.0 / mem[1]);
            line.append(Math.max(0, Math.min(100, percent))).append('%');
        }
        return StatsText.forLocale(line.toString(), getResources().getConfiguration().getLocales().get(0));
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    /** RAM usage as {used, total} in MB, used = MemTotal - MemAvailable, e.g. "945 / 1536 MB". Used over
     *  free so the line reads as a load against a known whole; null when /proc/meminfo cannot be read. */
    private long[] memUsedOfTotalMb() {
        long available = -1, total = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            String[] t;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) available = Long.parseLong(t[1]) / 1024;
                } else if (line.startsWith("MemTotal:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) total = Long.parseLong(t[1]) / 1024;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        if (available < 0 || total <= 0) return null;
        return new long[]{Math.max(0, total - available), total};
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(Resources res, long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? res.getString(R.string.storage_gb_whole, gb)
                : res.getString(R.string.storage_gb_decimal, gb);
    }

    /** Give the rail its own lightweight gradient and focus-color glow, no bitmap allocations. */
    private void configureVerticalRail() {
        ViewGroup oldParent = (ViewGroup) grid.getParent();
        oldParent.removeView(grid);
        oldParent.setVisibility(View.GONE);

        verticalRailBackdrop = new RowaVerticalRailView(this);
        FrameLayout.LayoutParams backdrop = new FrameLayout.LayoutParams(
                Math.round(266f * density), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.TOP);
        root.addView(verticalRailBackdrop, root.indexOfChild(glass), backdrop);

        FrameLayout.LayoutParams rail = new FrameLayout.LayoutParams(
                Math.round(214f * density), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.TOP);
        rail.leftMargin = Math.round(22f * density);
        root.addView(grid, root.indexOfChild(glass), rail);
        // Keep the very first and last cards completely visible, while the edge
        // masks soften cards that continue beyond the viewport during scrolling.
        grid.setPadding(Math.round(8f * density), Math.round(64f * density),
                Math.round(8f * density), Math.round(68f * density));
        grid.setClipToPadding(false);

        // Anchor the focused app title in the root's LAYOUT, after the rail's actual
        // configured right edge.  View.setX() must not be used here: showHero's
        // translationX animations reset it to the XML's original 68dp position.
        // The layout margin survives every fade, label change and focus animation.
        int titleInset = rail.leftMargin + rail.width + Math.round(30f * density);
        FrameLayout.LayoutParams heroLp = (FrameLayout.LayoutParams) hero.getLayoutParams();
        heroLp.gravity = Gravity.TOP | Gravity.START;
        heroLp.setMarginStart(titleInset);
        hero.setLayoutParams(heroLp);
        hero.setTranslationX(0f);
    }

    /** The button row stays at top-right; the complete greeting/clock/date/weather block follows below. */
    private void configureVerticalHeader() {
        View left = findViewById(R.id.leftBlock);
        View buttons = findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams bp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        bp.removeRule(RelativeLayout.ALIGN_BOTTOM);
        bp.removeRule(RelativeLayout.ALIGN_TOP);
        bp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        bp.addRule(RelativeLayout.ALIGN_PARENT_END);
        bp.bottomMargin = 0;
        buttons.setLayoutParams(bp);

        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) left.getLayoutParams();
        lp.removeRule(RelativeLayout.ALIGN_PARENT_START);
        lp.removeRule(RelativeLayout.START_OF);
        lp.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
        lp.addRule(RelativeLayout.BELOW, R.id.topButtons);
        lp.addRule(RelativeLayout.ALIGN_PARENT_END);
        lp.rightMargin = 0;
        lp.leftMargin = 0;
        lp.setMarginEnd(0);
        lp.setMarginStart(0);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        lp.width = Math.max(Math.round(280f * density),
                Math.min(Math.round(670f * density), screenWidth - Math.round(325f * density)));
        lp.topMargin = Math.round(12f * density);
        lp.bottomMargin = 0;
        left.setLayoutParams(lp);
        // Right-align the CONTENTS as well as the container. A right-aligned
        // container with left-aligned TextViews was why the clock appeared at
        // the middle of the screen rather than under the right-hand buttons.
        greeting.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        View clockRow = (View) findViewById(R.id.clock).getParent();
        if (clockRow instanceof LinearLayout)
            ((LinearLayout) clockRow).setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);

        View statistics = findViewById(R.id.stats);
        // show_stats moves this view into the LinearLayout topButtons. Never cast its
        // LinearLayout.LayoutParams to RelativeLayout.LayoutParams (ClassCastException).
        if (statistics.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams sp = (RelativeLayout.LayoutParams) statistics.getLayoutParams();
            sp.removeRule(RelativeLayout.ALIGN_START);
            sp.addRule(RelativeLayout.ALIGN_END, R.id.leftBlock);
            statistics.setLayoutParams(sp);
        }
        // Keep long titles inside the space after the rail, without imposing a
        // minimum that could push text back over the rail on small displays.
        FrameLayout.LayoutParams heroLp = (FrameLayout.LayoutParams) hero.getLayoutParams();
        int titleInset = heroLp.getMarginStart();
        heroName.setMaxWidth(Math.max(1,
                screenWidth - titleInset - Math.round(28f * density)));
        heroName.setEllipsize(TextUtils.TruncateAt.END);
        heroMeta.setVisibility(View.GONE);
        heroDots.setVisibility(View.GONE);
    }

    /** Unlike firstTopButton(), this includes the Inputs pill when it is visible. */
    private View firstVerticalTopButton() {
        ViewGroup bar = findViewById(R.id.topButtons);
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isFocusable()) return child;
        }
        return findViewById(R.id.btnSettings);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        int target;
        if (verticalRail) {
            // App column is on the left. RIGHT always reaches the top action row; LEFT stays in rail.
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                firstVerticalTopButton().requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) target = position - 1;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) target = position + 1;
            else return false;
            if (target < 0) return true;
            if (target >= count) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            focusTile(target);
            grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                    ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
            return true;
        }
        if (singleRow) {
            // One row: Up leaves for the top bar, Down has nowhere to go, and Left/Right walk the whole row,
            // stopping at its two ends (the default focus search scrolls the row to the next tile).
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            int last = adapter.focusablePosition(count - 1);
            if (forward && position >= last) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            return !forward && position == 0;
        }
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)) {
                focusLibrary();
                return true;
            }
            // In the dock, the last app is the row's end: the empty slots after it take no focus.
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // The default focus search clicks for Left/Right; Up/Down are handled here, so click for them too.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    /**
     * date_below_clock: the date column moves to its own line under the clock, in the vertical LinearLayout that holds
     * the clock's row, and the line between them goes. In Hebrew (rtl_menu_bar) the date and the clock's row sit right.
     */
    private void moveDateBelowClock() {
        View column = (View) findViewById(R.id.date).getParent();
        View clockRow = (View) findViewById(R.id.clock).getParent();
        LinearLayout clockColumn = (LinearLayout) clockRow.getParent();
        ((ViewGroup) column.getParent()).removeView(column);
        findViewById(R.id.clockDivider).setVisibility(View.GONE);
        // Its own width, not the column's: in Hebrew the column is only as wide as the clock.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(4 * density);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            // Date and clock flush right; the greeting stays left and follows the clock by translation.
            lp.gravity = Gravity.RIGHT;
            ((LinearLayout.LayoutParams) clockRow.getLayoutParams()).gravity = Gravity.RIGHT;
        }
        clockColumn.addView(column, clockColumn.indexOfChild(clockRow) + 1, lp);
    }

    /** The first top-bar button after Inputs that is shown, in bar order (top_bar_apps, the network pill, ...); Settings is always there. */
    private View firstTopButton() {
        ViewGroup bar = findViewById(R.id.topButtons);
        for (int i = 0; i < bar.getChildCount(); i++) {
            View button = bar.getChildAt(i);
            if (button != btnInputs && button.getVisibility() == View.VISIBLE) return button;
        }
        return findViewById(R.id.btnSettings);
    }

    /** top_bar_apps buttons, in bar order. */
    private final List<View> topAppButtons = new ArrayList<>();

    /**
     * Puts each top_bar_apps app in the top bar as a round pill with the app's own logo, left of the network pill.
     * The logo keeps its colours: the pill style's tint, which turns the other icons white, is cleared.
     */
    private void addTopBarApps(List<String> packages) {
        ViewGroup bar = findViewById(R.id.topButtons);
        PackageManager pm = getPackageManager();
        int size = Math.round(22 * density);
        for (String pkg : packages) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;   // not installed, or disabled
            Drawable logo;
            CharSequence label;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                logo = info.loadIcon(pm);
                label = info.loadLabel(pm);
                AppAppearance.Artwork visual = AppAppearance.artwork(this, pkg);
                if (visual != null) logo = visual.drawable;
                label = AppAppearance.label(this, pkg, label.toString());
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            TextView button = (TextView) getLayoutInflater().inflate(R.layout.top_app_button, bar, false);
            // pillKeys finds the bar's ends by id: without one of its own, every app button would be an end.
            button.setId(View.generateViewId());
            logo.setBounds(0, 0, size, size);
            button.setCompoundDrawablesRelative(logo, null, null, null);
            button.setCompoundDrawableTintList(null);
            button.setContentDescription(label);
            Intent target = launch;
            button.setOnClickListener(v -> open(new Intent(target)));
            bar.addView(button, bar.indexOfChild(btnWifi));
            topAppButtons.add(button);
        }
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { firstTopButton().requestFocus(); return; }
        int lastRow = verticalRail ? adapter.appCount() - 1
                : ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else firstTopButton().requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        int visibility = valid ? View.VISIBLE : View.INVISIBLE;
        if (clockBlock.getVisibility() != visibility) clockBlock.setVisibility(visibility);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        // Locale-specific day parts (app language on Android 13+).
        // Indonesian: Pagi 00-10, Siang 10-15, Sore 15-18, Malam 18-24.
        // Spain (es-ES) and Catalan: "Buenas tardes" into the evening; "Buenas noches" from 21.
        // Latin American Spanish (es-MX, es-AR, вЂ¦) keeps the default hours: evening strings are also
        // В«Buenas nochesВ», and there В«Buenas nochesВ» from ~17 matches local use better than Spain's 21.
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if ("in".equals(lang) || "id".equals(lang)) {
            if (hour < 10) res = R.string.good_morning;
            else if (hour < 15) res = R.string.good_afternoon;
            else if (hour < 18) res = R.string.good_evening;
            else res = R.string.good_night;
        } else if (("es".equals(lang) && "ES".equals(country)) || "ca".equals(lang)) {
            if (hour < 6) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 21) res = R.string.good_afternoon;
            else res = R.string.good_night;
        } else if ("da".equals(lang)) {
            // Danish day parts: "God morgen" until 11, then "God formiddag" to noon.
            if (hour < 5) res = R.string.good_night;
            else if (hour < 11) res = R.string.good_morning;
            else if (hour < 12) res = R.string.good_forenoon;
            else if (hour < 18) res = R.string.good_afternoon;
            else if (hour < 22) res = R.string.good_evening;
            else res = R.string.good_night;
        } else {
            if (hour < 5) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 17) res = R.string.good_afternoon;
            else if (hour < 22) res = R.string.good_evening;
            else res = R.string.good_night;
        }
        String festiveGreeting = festive ? festival.greetingText(getResources()) : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(RowaPersonalization.name(this, greetingName).isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), RowaPersonalization.name(this, greetingName)));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today(getResources()));
        if (lunar) lunarDate.setText(Lunar.today(getResources(), lunarOffset));
    }

    // ---- race-weekend panel (a theme's race_calendar) -----------------------------------------------

    private static final int RACE_RED = 0xFFFF3B2F;

    /**
     * Shows or hides the race-weekend panel for this minute. Text only, set once a minute with the clock:
     * nothing animates, so the panel costs the GPU nothing between ticks.
     */
    private void updateRace() {
        if (raceCalendar.isEmpty()) return;
        RaceWeekend.State s = RaceWeekend.at(raceCalendar, java.time.Instant.now());
        if (s == null) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        RaceWeekend.Race race = s.race;
        raceKicker.setText(race.place.isEmpty()
                ? getString(R.string.race_kicker_no_place, race.round, race.shortName())
                : getString(R.string.race_kicker, race.round, race.shortName(), race.place));

        if (s.live != null) {
            SpannableString live = new SpannableString("\u25CF  " + getString(R.string.race_live, s.live.title(getResources())));
            live.setSpan(new android.text.style.ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            raceHeadline.setText(live);
        } else if (s.next != null) {
            String in = RaceWeekend.countdown(getResources(), s.untilNext);
            raceHeadline.setText(s.next.isRace() ? getString(R.string.race_lights_out, in)
                    : getString(R.string.race_next, s.next.title(getResources()), in));
        } else {
            raceHeadline.setText(R.string.race_finished);
        }

        // FP1 FRI 11:00 PM   FP2 SAT 2:30 AM ...: done sessions fade, the live one is red, the next one bright.
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(this) ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault());
        android.text.SpannableStringBuilder row = new android.text.SpannableStringBuilder();
        for (int i = 0; i < race.sessions.size(); i++) {
            RaceWeekend.Session session = race.sessions.get(i);
            if (i > 0) row.append("     ");
            int start = row.length();
            row.append(session.shortName(getResources())).append("  ").append(when.format(session.start).toUpperCase(Locale.getDefault()));
            int color;
            switch (s.statuses.get(i)) {
                case DONE: color = 0x47FFFFFF; break;
                case LIVE: color = RACE_RED; break;
                case NEXT: color = 0xFFFFFFFF; break;
                default: color = 0x99FFFFFF;
            }
            row.setSpan(new android.text.style.ForegroundColorSpan(color), start, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        raceSessions.setText(row);
        racePanel.setVisibility(View.VISIBLE);
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false, validated = false;
        int icon = R.drawable.ic_wifi;
        // The diya's flame: 0 is out (offline), 1 to 4 follow the Wi-Fi signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                // Android reached the internet through it; a captive portal stays unvalidated until login.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) validated = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The Wi-Fi icon and its
            // gap are counted by hand: until the pill has a resolved layout direction (the first call, from
            // onResume, runs before that) getTotalPaddingStart/End leave the compound drawables out, which
            // left the name about 31dp too much room and the badge cut off.
            TextPaint paint = btnWifi.getPaint();
            Drawable[] compound = btnWifi.getCompoundDrawablesRelative();
            int iconRoom = compound[0] != null ? compound[0].getIntrinsicWidth() + btnWifi.getCompoundDrawablePadding() : 0;
            float room = btnWifi.getMaxWidth() - btnWifi.getPaddingStart() - btnWifi.getPaddingEnd() - iconRoom
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            String name = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A long badge ("VPN РѕС‚РєР»СЋС‡С‘РЅ") can leave room for a stub like "MTвЂ¦", which says nothing and, after
            // rounding, can push the badge itself out of the pill. Show the badge alone then. On Ethernet the
            // icon already names the link, and "Ethernet" cut to "EtheвЂ¦" or not depended on the font size (#961),
            // so Ethernet always shows the icon and the badge alone.
            // The space stays even then: a line holding only the span gets no font metrics and collapses.
            boolean stub = name.length() < text.length() && name.length() < 5;
            text = stub || icon == R.drawable.ic_ethernet ? "" : name;
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.equals(btnWifi.getText(), label)) btnWifi.setText(label);
        } else if (!TextUtils.equals(btnWifi.getText(), text)) {
            btnWifi.setText(text);
        }
        if (wifiIconOnly) {
            btnWifi.setContentDescription(btnWifi.getText());
            btnWifi.setText(null);
        }
        if (festive && diya != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            diya.setSteady(steady);
            diya.setFlame(flame);
        } else {
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
            ColorStateList tint = online ? wifiOnlineTint : wifiPlainTint;
            // wifi_status_warnings: orange while connected but not validated (a captive portal), red while offline.
            if (wifiNoInternetTint != null) tint = !online ? wifiOfflineTint : validated ? wifiOnlineTint : wifiNoInternetTint;
            if (wifiOnlineTint != null && btnWifi.getCompoundDrawableTintList() != tint) {
                btnWifi.setCompoundDrawableTintList(tint);
            }
        }
        rowaApplyNetworkPreference();
    }

    /** config.xml icon_only_buttons. */
    private boolean iconOnlyButtons;
    /** config.xml top_menu: Inputs, Wi-Fi and Settings folded into one gear. */
    private boolean topMenu;
    /** show_inputs_pill as configured: top_menu clears showInputsPill, so its Inputs row reads this. */
    private boolean showInputsMenuRow = true;
    private boolean launcherSettingsButton;
    /** icon_only_buttons without wifi_label_width: the Wi-Fi pill shows no network name. */
    private boolean wifiIconOnly;
    /** config.xml wifi_status_color: the Wi-Fi icon's green while online and its usual pill colours; null while off. */
    private ColorStateList wifiOnlineTint, wifiPlainTint;
    /** wifi_status_warnings (with wifi_status_color): orange without internet, red offline; null while off. */
    private ColorStateList wifiNoInternetTint, wifiOfflineTint;
    /** The app library button in the top bar (library_button). */
    private View btnLibraryTop;
    /** The open library came from that button: closing it returns there instead of scrolling to the grid's end. */
    private boolean libraryFromTopBar;

    /** Turns a labelled top pill into the round TopButtonIcon shape: no text, the icon centred by even padding. */
    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        int pad = Math.round(10 * density);
        pill.setPaddingRelative(pad, 0, pad, 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = Math.round(42 * density);
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    // ROWA_HEADER_V2: TV-only header controls. No background service and no accessibility key filter.
    private static final String ROWA_HEADER_PREFS = "rowa_header_preferences";

    /** Assemble the same controls in a single header row, not a new launcher. */
    private void rowaSetupHeader() {
        rowaPositionWeatherAfterDate();

        if (stats != null && showStats) {
            android.view.ViewGroup old = (android.view.ViewGroup) stats.getParent();
            if (old != null) old.removeView(stats);
            LinearLayout bar = findViewById(R.id.topButtons);
            int settingsIndex = bar.indexOfChild(findViewById(R.id.btnSettings));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(42 * density));
            lp.setMargins(Math.round(10 * density), 0, 0, 0);
            bar.addView(stats, Math.max(0, settingsIndex), lp);
            stats.setGravity(Gravity.CENTER);
            stats.setTextSize(14);
            rowaStyleStatsPill();
            stats.setSingleLine(true);
            stats.setFocusable(true);
            stats.setClickable(true);
            stats.setContentDescription("CPU and RAM. Hold OK for recently used apps.");
            stats.setOnClickListener(v -> rowaShowAppsMenu());
            stats.setOnLongClickListener(v -> { rowaShowAppsMenu(); return true; });
            // The existing stats reader still runs only while Home is resumed, every ten seconds.
        }

        btnWifi.setOnClickListener(v -> rowaOpenPreferredNetwork());
        btnWifi.setOnLongClickListener(v -> { rowaShowNetworkMenu(); return true; });
        rowaApplyNetworkPreference();
    }

    /** Change which settings page an ordinary OK press opens. Persist over reboots. */
    private boolean rowaBluetoothPreferred() {
        return getSharedPreferences(ROWA_HEADER_PREFS, MODE_PRIVATE)
                .getBoolean("bluetooth_default", false);
    }

    private void rowaChooseNetwork(boolean bluetooth) {
        getSharedPreferences(ROWA_HEADER_PREFS, MODE_PRIVATE).edit()
                .putBoolean("bluetooth_default", bluetooth).apply();
        wifiIcon = -1; // Make sure the original Wi-Fi drawable is restored after Bluetooth.
        updateWifiLabel();
    }

    // ROWA_HEADER_V5: TV-aware settings launch. Do not depend on SystemSettings vendor fallback.
    // Many Google TV firmware builds do not expose ACTION_BLUETOOTH_SETTINGS.
    // ROWA_UI_V7: prefer the TV's accessories page or pairing activity to generic Settings.
    // An OEM may not export the connected-devices page. Never claim it opened a page
    // we cannot resolve, and never change Bluetooth's state ourselves.
    /** ROWA_UI_V8: Google TV's actual connected-devices settings slice first.
     * ACTION_BLUETOOTH_SETTINGS on this ROWA resolves to generic Settings, so it
     * is intentionally NOT used as the first fallback. Firmware support varies.
     */
    /** ROWA_V10: Prefer the provider actually present in Android TV Settings,
     * not the unrelated btservices provider. The ROWA firmware may still ignore
     * the slice intent; no synthetic navigation or accessibility key injection. */
    // ROWA_V11: This firmware exports Settings, not a confirmed direct accessories Activity.
    // Instead of failing with a toast, give two useful native routes.
    // ROWA_V12: native pairing first, native accessory page second; never mislabel Settings root.
    // ROWA_V14: let the PopupWindow release focus before starting TV Settings.
    // Both mouse/touch clicks and remote OK go through addSettingsMenuRow().
    private void rowaOpenBluetoothSettings() {
        View anchor = findViewById(R.id.btnWifi);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, Math.round(245 * density));
        addSettingsMenuRow(rows, popup, R.string.rowa_v12_bt_pair,
                () -> rowaRunBluetoothMenuAction("pair", this::rowaStartBluetoothPairing));
        addSettingsMenuRow(rows, popup, R.string.rowa_v12_bt_accessories,
                () -> rowaRunBluetoothMenuAction("accessories", this::rowaOpenTvAccessories));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(Math.round(245 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    // Called after the existing helper has dismissed its PopupWindow.
    private void rowaRunBluetoothMenuAction(String label, Runnable action) {
        android.util.Log.i("ROWA_BT_V14", "Bluetooth dropdown selected: " + label);
        root.postDelayed(() -> {
            android.util.Log.i("ROWA_BT_V14", "Running Bluetooth menu action: " + label);
            try {
                action.run();
            } catch (RuntimeException failure) {
                android.util.Log.e("ROWA_BT_V14", "Uncaught Bluetooth menu action: " + label, failure);
                Toast.makeText(this, "Cannot open TV Bluetooth settings; see ROWA_BT_V14 log",
                        Toast.LENGTH_LONG).show();
            }
        }, 150);
    }

    // ROWA_V13: direct Android TV Settings slice, based on installed provider
    // com.android.tv.settings.accessories.sliceprovider. No misleading Settings-root fallback.
    // ROWA_V14: same intent proven by ADB v13 -a/-p/--es invocation.
    // This may still be blocked for third-party launchers by OEM permissions.
    private void rowaOpenTvAccessories() {
        final String uri = "content://com.android.tv.settings.accessories.sliceprovider/general";
        final String action = "android.settings.SLICE_SETTINGS";
        final Intent implicit = new Intent(action)
                .setPackage("com.android.tv.settings")
                .putExtra("slice_uri", uri);
        try {
            android.util.Log.i("ROWA_BT_V14", "Launching implicit Settings slice");
            startActivity(implicit);
            android.util.Log.i("ROWA_BT_V14", "Implicit Settings startActivity returned normally");
            return;
        } catch (ActivityNotFoundException | SecurityException denied) {
            android.util.Log.e("ROWA_BT_V14", "Implicit Settings slice rejected", denied);
        }
        final Intent explicit = new Intent(action)
                .setClassName("com.android.tv.settings", "com.android.tv.settings.slice.SliceActivity")
                .putExtra("slice_uri", uri);
        try {
            android.util.Log.i("ROWA_BT_V14", "Launching explicit Settings slice");
            startActivity(explicit);
            android.util.Log.i("ROWA_BT_V14", "Explicit Settings startActivity returned normally");
            return;
        } catch (ActivityNotFoundException | SecurityException denied) {
            android.util.Log.e("ROWA_BT_V14", "Explicit Settings slice rejected", denied);
        }
        Toast.makeText(this, R.string.rowa_v12_accessories_unavailable, Toast.LENGTH_LONG).show();
    }

    private void rowaOpenTvSettings() {
        rowaOpenTvAccessories();
    }

    private void rowaOpenPreferredNetwork() {
        if (rowaBluetoothPreferred()) {
            rowaOpenBluetoothSettings();
        } else {
            openNetworkPill();
        }
    }

    /** Invoked after the original WifiManager/status logic so live Wi-Fi updates don't reset the choice. */
    private void rowaApplyNetworkPreference() {
        if (btnWifi == null || !rowaBluetoothPreferred()) return;
        btnWifi.setText(wifiIconOnly ? null : "Bluetooth");
        btnWifi.setContentDescription("Bluetooth settings. Hold OK to select Wi-Fi instead.");
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.rowa_ic_bluetooth, 0, 0, 0);
    }

    // ROWA_HEADER_STYLE_V3: reuse the exact row drawable, focus behavior and fonts of Settings.
    private void rowaStyleStatsPill() {
        if (stats == null) return;
        stats.setBackgroundResource(R.drawable.pill_bg);
        stats.setTextColor(getResources().getColorStateList(R.drawable.pill_text, getTheme()));
        stats.setPadding(Math.round(16 * density), 0, Math.round(20 * density), 0);
        stats.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        stats.setLetterSpacing(0.01f);
        stats.setGravity(Gravity.CENTER);
        stats.setSingleLine(true);
    }

    /** Shared settings dropdown row builder for resource-backed and dynamic labels. */
    private View rowaAddSettingsStyleRow(LinearLayout rows, PopupWindow popup,
                                          CharSequence title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // Match Settings: ignore the OK-release that follows the hold which opened this menu.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        RowaMenuMarquee.bind(row, (TextView) row.findViewById(R.id.title));
        rows.addView(row);
        return row;
    }

    private void rowaShowNetworkMenu() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, Math.round(200 * density));
        View wifi = rowaAddSettingsStyleRow(rows, popup, "Wi-Fi", () -> rowaChooseNetwork(false));
        View bluetooth = rowaAddSettingsStyleRow(rows, popup, getString(R.string.rowa_bt_name), () -> rowaChooseNetwork(true));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(Math.round(200 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, btnWifi, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        (rowaBluetoothPreferred() ? bluetooth : wifi).requestFocus();
    }

    /** Move the existing weather TextView and separator; leave the Clock and date TextClock intact. */
    /** ROWA_HEADER_STYLE_V3: plain icon + temperature; divider only between date and weather. */
    private void rowaPositionWeatherAfterDate() {
        View weather = findViewById(R.id.rowaWeather);
        View divider = findViewById(R.id.clockDivider);
        View date = findViewById(R.id.date);
        View clock = findViewById(R.id.clock);
        if (weather == null || divider == null || date == null || clock == null) return;
        if (!(clock.getParent() instanceof LinearLayout)
                || !(date.getParent() instanceof LinearLayout)) return;
        LinearLayout clockRow = (LinearLayout) clock.getParent();
        LinearLayout dateColumn = (LinearLayout) date.getParent();
        if (weather.getParent() != clockRow || divider.getParent() != clockRow
                || dateColumn.getParent() != clockRow) return;
        clockRow.removeView(weather);
        clockRow.removeView(divider);
        clockRow.removeView(dateColumn);

        // No custom pill, border, tint, or weather background. Just its weather symbol and Celsius value.
        weather.setBackground(null);
        weather.setPadding(0, 0, 0, 0);
        if (weather instanceof TextView) {
            ((TextView) weather).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            ((TextView) weather).setTextColor(0xE6FFFFFF);
        }

        LinearLayout trailing = new LinearLayout(this);
        trailing.setOrientation(LinearLayout.HORIZONTAL);
        trailing.setGravity(Gravity.BOTTOM);
        trailing.addView(dateColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                Math.max(1, Math.round(density)), Math.round(24 * density));
        dividerLp.setMargins(Math.round(5 * density), 0,
                Math.round(8 * density), Math.round(8 * density));
        trailing.addView(divider, dividerLp);
        LinearLayout.LayoutParams weatherLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weatherLp.bottomMargin = Math.round(9 * density);
        trailing.addView(weather, weatherLp);
        divider.setVisibility(weather.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        weather.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            int wanted = v.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE;
            if (divider.getVisibility() != wanted) divider.setVisibility(wanted);
        });
        clockRow.addView(trailing, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** Android 14 doesn't let third-party launchers enumerate/force-stop every running app.
     * Show recently used apps obtained with Usage Access; opening each app's Info page
     * lets the owner press the TV's real Force Stop control, with confirmation.
     */
    /** ROWA_HEADER_STYLE_V3: Settings-style recent-app chooser (not a running-process/kill-all UI). */
    private void rowaShowAppsMenu() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, Math.round(265 * density));
        rowaAddSettingsStyleRow(rows, popup, getString(R.string.rowa_manage_all_apps),
                () -> open(systemIntent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
        java.util.List<android.app.usage.UsageStats> recent = rowaRecentApps();
        if (recent.isEmpty()) {
            rowaAddSettingsStyleRow(rows, popup, getString(R.string.rowa_usage_access_recent),
                    () -> open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        } else {
            java.util.Set<String> seen = new java.util.HashSet<>();
            int shown = 0;
            for (android.app.usage.UsageStats item : recent) {
                final String pkg = item.getPackageName();
                if (!seen.add(pkg)) continue;
                try {
                    android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                    if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                            || pkg.equals(getPackageName())) continue;
                    if (getPackageManager().getLeanbackLaunchIntentForPackage(pkg) == null
                            && getPackageManager().getLaunchIntentForPackage(pkg) == null) continue;
                    final String label = getPackageManager().getApplicationLabel(ai).toString();
                    rowaAddSettingsStyleRow(rows, popup, label,
                            () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + pkg))));
                    if (++shown >= 10) break;
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                    // An uninstalled or invisible application is not offered.
                }
            }
            if (shown == 0) {
                rowaAddSettingsStyleRow(rows, popup, getString(R.string.rowa_open_app_settings),
                        () -> open(systemIntent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
            }
        }
        // Exactly the Settings menu's row styling, but constrained in height for a small TV.
        // This is the shared scrollable popup, not a nested second ScrollView.
        android.widget.ScrollView scroll = panel.findViewById(R.id.popupScroll);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.round(265 * density));
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.round(330 * density)));
        dropIn(popup, panel, stats, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private java.util.List<android.app.usage.UsageStats> rowaRecentApps() {
        android.app.usage.UsageStatsManager usage =
                (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usage == null) return java.util.Collections.emptyList();
        long now = System.currentTimeMillis();
        java.util.List<android.app.usage.UsageStats> list =
                usage.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                        now - 12L * 60 * 60 * 1000, now);
        if (list == null) return java.util.Collections.emptyList();
        java.util.Collections.sort(list,
                (a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        return list;
    }


    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /** Every settings entry shares the standard intent and the vendor fallback (SystemSettings). */
    private Intent systemIntent(String standardAction) {
        return SystemSettings.intent(this, standardAction);
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        if (!showInputsPill) {
            btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty()
                || (coocaaSources && !CoocaaSources.list(this).isEmpty());
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** boot_to_live_tv, or the Open on start app: the first time Home comes up after the TV starts, open that app
     *  instead. Once per boot, keyed on Settings.Global.BOOT_COUNT and committed to disk before the app starts: on a
     *  low-memory TV the app can push the launcher out of memory, and an apply() still in flight was lost then, so
     *  every Home press opened the app again. */
    private boolean openLiveTvAfterBoot() {
        String app = openOnStartChoice ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(OpenOnStart.KEY, "") : "";
        if (!bootToLiveTv && app.isEmpty()) return false;
        int boot = OpenOnStart.boot(this);
        SharedPreferences prefs = getSharedPreferences(OpenOnStart.BOOT_PREFS, MODE_PRIVATE);
        String target = bootToLiveTv ? liveTvPkg : app;
        if (!OpenOnStart.due(boot, prefs.getInt(OpenOnStart.BOOT_KEY, -1), target, getPackageName())) return false;
        if (!prefs.edit().putInt(OpenOnStart.BOOT_KEY, boot).commit()) return false;
        Intent i = bootToLiveTv ? liveTvIntent() : OpenOnStart.intent(getPackageManager(), app);
        if (i == null) return false;
        open(i);
        return true;
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9): a
     *  service, or an activity on sets whose panel is a dialog (BYINTEK U4's QuickSourceActivity). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        int eq = sourcePanelExtra.indexOf('=');
        if (eq > 0) {
            try {
                i.putExtra(sourcePanelExtra.substring(0, eq).trim(),
                        Integer.parseInt(sourcePanelExtra.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            if (getPackageManager().resolveActivity(i, 0) != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** `input_sources` in config.xml: "HW5=23" maps the framework input whose id ends in /HW5 to a vendor
     *  source number; "HW8=-" leaves that input out of the Inputs menu (a port the board reports but the set
     *  does not have). Returns the number, -1 for a left-out input, or null when the input is not listed. */
    private Integer mappedSource(String inputId) {
        if (inputId == null) return null;
        String hw = inputId.substring(inputId.lastIndexOf('/') + 1);
        for (String m : getResources().getStringArray(R.array.input_sources)) {
            int eq = m.indexOf('=');
            if (eq <= 0 || !m.substring(0, eq).trim().equals(hw)) continue;
            String v = m.substring(eq + 1).trim();
            if (v.equals("-")) return -1;
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Switches to a passthrough input (HDMI, AV). An input mapped in `input_sources` is opened by sending
     *  `input_action` with its source number as the int extra "input" (Xiaomi China firmware, whose tvplayer
     *  answers com.xiaomi.mitv.tvplayer.EXTSRC_PLAY and nothing else). Otherwise the standard way is
     *  ACTION_VIEW on its channel URI, which the Live TV app handles; where nothing takes that, the
     *  configured `input_action` is sent with the same URI instead (XGIMI's HDMI player only answers
     *  com.xgimi.action.hdmiPlayer). With `input_uri_scheme` set (YaOS: live-tv) the same URI goes out
     *  with that scheme and no MIME type. A player guarded by a permission this build does not declare
     *  throws SecurityException, which used to take Home down with it (YaOS before the manifest asked
     *  for ACCESS_TV_SOURCE). */
    private void openInput(String inputId) {
        // A CEC device is a child input of its HDMI port; the vendor source number belongs to the port
        // (MiTV-MFFU0). Only that lookup uses the parent: the generic path below still opens the child.
        String sourceId = inputId;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                TvInputInfo info = tim.getTvInputInfo(inputId);
                if (info != null && info.getParentId() != null) sourceId = info.getParentId();
            } catch (RuntimeException ignored) {
            }
        }
        Integer source = mappedSource(sourceId);
        if (source != null && source >= 0 && !inputAction.isEmpty()) {
            try {
                startActivity(new Intent(inputAction).putExtra("input", source.intValue())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(inputUriScheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Aim it at the TV app first: apps installed later (a streaming app, a file manager) can claim the same
        // channel URI too, and with two or more handlers Android shows a chooser instead of the input (WhaleOS).
        if (!liveTvPkg.isEmpty()) {
            try {
                startActivity(new Intent(view).setPackage(liveTvPkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        // A configured source panel and no Live TV row to offer next to it: the panel is the whole menu.
        // The BYINTEK U4 needs this, as its TvInputManager lists three HDMI ports and an AV input it doesn't have.
        List<CoocaaSources.Source> coocaa = coocaaSources ? CoocaaSources.list(this) : Collections.emptyList();
        if (coocaa.isEmpty() && !sourcePanelService.isEmpty() && liveTvIntent() == null) {
            openSourcePanel();
            return;
        }
        // top_menu: the pill is gone, so the list hangs under the gear, growing inward from the right.
        // Read the switch, not the pill: show_inputs_pill=false hides the pill too, and there the list
        // keeps its usual place.
        boolean underGear = topMenu;
        View anchor = findViewById(underGear ? R.id.btnSettings : R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (liveTvInputId.isEmpty() || liveTvInputId.equals(info.getId()))) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                // input_sources can leave out ports the board reports but the set does not have ("HW8=-").
                if (Integer.valueOf(-1).equals(mappedSource(info.getId()))) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = InputNames.tidy(info.loadCustomLabel(this));
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV В· HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = InputNames.tidy(device.loadCustomLabel(this));
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus ? -1 : tim.getInputState(deviceId);
                        addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
                // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(id);
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Coocaa OS registers nothing with TvInputManager; com.skyworth.tv lists its sources instead.
        if (rows.getChildCount() == 0) {
            for (CoocaaSources.Source s : coocaa) {
                int icon = "HDMI".equals(s.name) ? R.drawable.ic_input
                        : "VOD".equals(s.name) ? R.drawable.ic_livetv : R.drawable.ic_av;
                addInputRow(rows, popup, icon, s.label, -1, () -> {
                    if (!CoocaaSources.open(this, s)) {
                        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            // An explicit tuner uses its channel collection (e.g. DVB-S rather than DVB-T/C).
            // Keep the legacy passthrough route for automatic selection: some OEM players need it.
            // If the configured input is absent, tunerId stays null and the app opens normally.
            boolean configured = !liveTvInputId.isEmpty();
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(configured ? TvContract.buildChannelsUriForInput(tunerId)
                                    : TvContract.buildChannelUriForPassthroughInput(tunerId),
                            configured ? TvContract.Channels.CONTENT_TYPE : TvContract.Channels.CONTENT_ITEM_TYPE)
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) != underGear ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        rowaV15ApplyPanelAlpha(panel);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        fitDropdownToScreen(popup, panel, anchor);
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /** Fit every shared dropdown into the current display. The rows stay focusable and
     * the inner scroll view handles Up/Down when more items are present than fit. */
    private void fitDropdownToScreen(PopupWindow popup, View panel, View anchor) {
        android.widget.ScrollView scroll = panel.findViewById(R.id.popupScroll);
        if (scroll == null) return;
        int height = getResources().getDisplayMetrics().heightPixels;
        int[] origin = new int[2];
        anchor.getLocationOnScreen(origin);
        int below = height - origin[1] - anchor.getHeight() - Math.round(30 * density);
        int above = origin[1] - Math.round(24 * density);
        int capacity = Math.min(height - Math.round(36 * density),
                Math.max(Math.max(below, above), Math.round(110 * density)));
        // Respect specialized menu limits, such as the Appearance panel's 360dp cap.
        int configuredHeight = popup.getHeight();
        if (configuredHeight > 0) capacity = Math.min(capacity, configuredHeight);
        int width = Math.max(Math.round(140 * density), popup.getWidth());
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height * 3, View.MeasureSpec.AT_MOST));
        int natural = panel.getMeasuredHeight();
        if (natural > capacity) {
            int overhead = natural - scroll.getMeasuredHeight();
            int rowsHeight = Math.max(Math.round(42 * density), capacity - overhead);
            android.view.ViewGroup.LayoutParams params = scroll.getLayoutParams();
            params.height = rowsHeight;
            scroll.setLayoutParams(params);
            popup.setHeight(Math.min(capacity, overhead + rowsHeight));
        } else popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private View addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        RowaMenuMarquee.bind(row, (TextView) row.findViewById(R.id.title), (TextView) row.findViewById(R.id.status));
        rows.addView(row);
        return row;
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** top_menu: the gear's dropdown, standing in for the Inputs, Wi-Fi and Settings pills. */
    private void showTopMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (220 * density));
        // Inputs opens its own list once this one has closed.
        if (showInputsMenuRow) addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, () -> root.post(this::showInputs));
        addInputRow(rows, popup, R.drawable.ic_wifi, getString(R.string.quick_network), -1, this::openNetworkPill);
        CharSequence network = wifiIconOnly ? btnWifi.getContentDescription() : btnWifi.getText();
        if (!TextUtils.isEmpty(network)) {
            TextView status = rows.getChildAt(rows.getChildCount() - 1).findViewById(R.id.status);
            status.setText(network);
            status.setVisibility(View.VISIBLE);
        }
        addInputRow(rows, popup, R.drawable.ic_swap_vert, getString(R.string.reorder), -1, this::toggleReorder);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.app_appearance_edit), -1,
                () -> startActivity(new Intent(this, AppAppearanceActivity.class)));
        if (launcherSettingsButton) {
            addInputRow(rows, popup, R.drawable.ic_home_settings_pill, getString(R.string.launcher_settings), -1,
                    () -> startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        } else {
            addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.settings), -1,
                    () -> open(systemIntent(Settings.ACTION_SETTINGS)));
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (220 * density), Math.min(panel.getMeasuredWidth(), (int) (360 * density))));
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /** The header opens launcher settings directly; hold OK keeps the extra home actions available. */
    // ROWA_APPEARANCE_V6: Focus-safe live appearance controls. Right/Left cycles values;
    // small dots show the selected value. Main, Home and volume remain untouched.
    private static String rowaAppearanceReopenKey;
    private GlassView rowaGlowPreview;

    // ROWA_V11: native SeekBar + D-pad support, reusing the existing focused Settings row.
    private void rowaMarginSlider(LinearLayout rows) {
        LinearLayout row = (LinearLayout) getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setTag("margins");
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.removeView(row.findViewById(R.id.status));
        ViewGroup.LayoutParams rlp = row.getLayoutParams();
        rlp.height = Math.round(68 * density);
        row.setLayoutParams(rlp);
        TextView title = row.findViewById(R.id.title);
        title.setText(R.string.rowa_v11_margins);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setLayoutParams(new LinearLayout.LayoutParams(Math.round(150 * density),
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView value = new TextView(this);
        value.setTextColor(getResources().getColorStateList(R.drawable.pill_text, getTheme()));
        value.setDuplicateParentStateEnabled(true);
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        value.setGravity(Gravity.CENTER);
        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setMax(100);
        slider.setProgress(RowaAppearance.marginPercent(this));
        slider.setFocusable(false);
        slider.setClickable(false);
        slider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(26 * density)));
        value.setText(RowaAppearance.marginPercent(this) + "%");
        controls.addView(value);
        controls.addView(slider);
        row.addView(controls);
        java.util.function.IntConsumer adjust = (amount) -> {
            int current = RowaAppearance.marginPercent(this);
            int next = Math.max(0, Math.min(100, current + amount));
            if (next == current) return;
            RowaAppearance.setMarginPercent(this, next);
            slider.setProgress(next);
            value.setText(next + "%");
            RowaAppearance.applyHeader(this);
        };
        row.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // One step per physical press, then accelerated repeat if held.
                if (event.getRepeatCount() == 0 || event.getRepeatCount() % 3 == 0)
                    adjust.accept(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 5 : -5);
            }
            return true;
        });
        row.setOnClickListener(v -> adjust.accept(5));
        rows.addView(row);
    }

    private void rowaWeatherLocationRow(LinearLayout rows) {
        LinearLayout row = (LinearLayout) getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setTag("weatherSource");
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView title = row.findViewById(R.id.title);
        title.setText(R.string.rowa_v11_weather_source);
        TextView status = row.findViewById(R.id.status);
        status.setText(RowaWeatherSettings.isDeviceMode(this)
                ? R.string.rowa_v11_device : R.string.rowa_v11_city);
        status.setVisibility(View.VISIBLE);
        row.setOnClickListener(v -> {
            if (openPopup != null) openPopup.dismiss();
            root.post(this::rowaWeatherSourceMenu);
        });
        rows.addView(row);
    }

    private void rowaWeatherSourceMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, Math.round(300 * density));
        addSettingsMenuRow(rows, popup, R.string.rowa_v11_device, () -> {
            RowaWeatherSettings.selectDevice(this);
        });
        addSettingsMenuRow(rows, popup, R.string.rowa_v11_city, this::rowaAskWeatherCity);
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    // ROWA_V12: live city autocomplete, using the same Open-Meteo geocoder as v11.
    private void rowaAskWeatherCity() {
        final android.widget.AutoCompleteTextView field =
                new android.widget.AutoCompleteTextView(this);
        field.setSingleLine(true);
        field.setThreshold(2);
        field.setDropDownHeight(Math.round(240 * density));
        field.setDropDownWidth(Math.min(Math.round(420 * density),
                getResources().getDisplayMetrics().widthPixels - Math.round(40 * density)));
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setText(RowaWeatherSettings.cityName(this));
        field.setSelectAllOnFocus(true);
        final int pad = Math.round(18 * density);
        field.setPadding(pad, pad, pad, pad);
        final java.util.ArrayList<RowaWeatherSettings.CityHit> hits = new java.util.ArrayList<>();
        final android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_dropdown_item_1line, new java.util.ArrayList<String>()) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView suggestion = (TextView) super.getView(position, convertView, parent);
                suggestion.setTextColor(getResources().getColorStateList(R.drawable.pill_text));
                suggestion.setBackgroundResource(R.drawable.input_row_bg);
                suggestion.setSingleLine(true);
                suggestion.setEllipsize(android.text.TextUtils.TruncateAt.END);
                int space = Math.round(12 * density);
                suggestion.setPadding(space, space, space, space);
                return suggestion;
            }
        };
        field.setAdapter(adapter);
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] sequence = {0};
        final boolean[] picked = {false};
        final RowaWeatherSettings.CityHit[] selected = {null};
        final Runnable[] pending = {null};
        field.setOnItemClickListener((parent, item, position, id) -> {
            String display = String.valueOf(adapter.getItem(position));
            selected[0] = null;
            for (RowaWeatherSettings.CityHit city : hits) {
                if (city.display.equals(display)) { selected[0] = city; break; }
            }
            picked[0] = true;
            sequence[0]++;
        });
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (picked[0]) { picked[0] = false; return; }
                selected[0] = null;
                if (pending[0] != null) ui.removeCallbacks(pending[0]);
                final int generation = ++sequence[0];
                final String query = s.toString().trim();
                if (query.length() < 2) {
                    adapter.clear(); adapter.notifyDataSetChanged(); return;
                }
                pending[0] = () -> RowaWeatherSettings.suggestCities(query, results -> {
                    if (generation != sequence[0] || field.getWindowToken() == null) return;
                    hits.clear(); hits.addAll(results);
                    adapter.clear();
                    for (RowaWeatherSettings.CityHit city : results) adapter.add(city.display);
                    adapter.notifyDataSetChanged();
                    if (!results.isEmpty() && field.hasFocus()) field.showDropDown();
                });
                ui.postDelayed(pending[0], 420L);
            }
            public void afterTextChanged(android.text.Editable text) { }
        });
        // Autocomplete suggestions use the same contrasting glass focus colors.
        field.setDropDownBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xF8262B34));
        android.app.Dialog dialog = RowaGlassDialog.input(this,
                getString(R.string.rowa_v11_city_prompt), field, null, () -> {
                    if (selected[0] != null && selected[0].display.equals(field.getText().toString()))
                        RowaWeatherSettings.selectResolvedCity(this, selected[0]);
                    else RowaWeatherSettings.selectCity(this, field.getText().toString().trim());
                }, null, null);
        dialog.setOnDismissListener(d -> {
            sequence[0]++;
            if (pending[0] != null) ui.removeCallbacks(pending[0]);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ROWA_BT_CONNECT_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
                rowaLaunchPairingActivity();
            else Toast.makeText(this, R.string.rowa_v12_pairing_unavailable, Toast.LENGTH_LONG).show();
        }
        if (requestCode == RowaWeatherSettings.LOCATION_REQUEST)
            RowaWeatherSettings.onPermissionResult(this);
    }

    // ROWA_V12: Appearance controls and user greeting, kept in the existing launcher UI.
    private void rowaGreetingNameRow(LinearLayout rows) {
        LinearLayout row = (LinearLayout) getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setTag("greetingName");
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView title = row.findViewById(R.id.title);
        title.setText(R.string.rowa_v12_greeting_name);
        TextView status = row.findViewById(R.id.status);
        String saved = RowaPersonalization.savedName(this);
        status.setText(saved.isEmpty() ? getString(R.string.rowa_v12_greeting_auto) : saved);
        status.setVisibility(View.VISIBLE);
        row.setOnClickListener(v -> {
            if (openPopup != null) openPopup.dismiss();
            root.post(this::rowaAskGreetingName);
        });
        rows.addView(row);
    }

    private void rowaAskGreetingName() {
        final android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setText(RowaPersonalization.savedName(this));
        field.setSelectAllOnFocus(true);
        int pad = Math.round(20f * density);
        field.setPadding(pad, pad, pad, pad);
        RowaGlassDialog.input(this, getString(R.string.rowa_v12_greeting_prompt),
                field, getString(R.string.rowa_v12_greeting_hint), () -> {
                    RowaPersonalization.save(this, field.getText().toString());
                    updateGreeting();
                }, getString(R.string.rowa_v12_greeting_clear), () -> {
                    RowaPersonalization.save(this, "");
                    updateGreeting();
                });
    }

    /** Use the launcher's existing focus/marquee component, not a new focus handler.
     *  Appearance v6 options already use it; v11's additional rows do not. */
    private void rowaFixAppearanceContrast(LinearLayout rows) {
        for (int n = 0; n < rows.getChildCount(); n++) {
            View row = rows.getChildAt(n);
            Object tag = row.getTag();
            if (!("margins".equals(tag) || "weatherSource".equals(tag)
                    || "greetingName".equals(tag))) continue;
            TextView title = row.findViewById(R.id.title);
            if (title == null) continue;
            java.util.ArrayList<TextView> others = new java.util.ArrayList<>();
            rowaGatherText(row, title, others);
            RowaMenuMarquee.bind(row, title, others.toArray(new TextView[0]));
        }
    }

    private void rowaGatherText(View current, TextView title,
                                java.util.ArrayList<TextView> others) {
        if (current instanceof TextView && current != title) {
            others.add((TextView) current);
        } else if (current instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) current;
            for (int i = 0; i < group.getChildCount(); i++)
                rowaGatherText(group.getChildAt(i), title, others);
        }
    }

    // Pairing is launched by Android TV Settings itself. Its activity may demand
    // BLUETOOTH_CONNECT on Android 12+; request only when the user selects Pair.
    private static final int ROWA_BT_CONNECT_REQUEST = 6122;
    private void rowaStartBluetoothPairing() {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission("android.permission.BLUETOOTH_CONNECT") !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"},
                    ROWA_BT_CONNECT_REQUEST);
            return;
        }
        rowaLaunchPairingActivity();
    }

    private void rowaLaunchPairingActivity() {
        Intent[] targets = new Intent[]{
                new Intent("com.google.android.intent.action.CONNECT_INPUT")
                        .setClassName("com.android.tv.settings",
                                "com.android.tv.settings.accessories.AddAccessoryActivity"),
                new Intent("com.google.android.intent.action.CONNECT_INPUT")
                        .setPackage("com.android.tv.settings"),
                new Intent("com.google.android.intent.action.CONNECT_INPUT")
        };
        for (Intent target : targets) {
            try {
                startActivity(target);
                return;
            } catch (ActivityNotFoundException | SecurityException denied) {
                android.util.Log.w("ROWA_BT_V12", "Pairing candidate unavailable: " + target, denied);
            }
        }
        Toast.makeText(this, R.string.rowa_v12_pairing_unavailable, Toast.LENGTH_LONG).show();
    }

    // ROWA_V13: intercept Back/ESC before focusable appearance rows consume keys.
    // Does not replace their D-pad Left/Right or OK handlers.
    private void rowaEnableAppearanceBack(final PopupWindow popup, View panel) {
        android.widget.FrameLayout backGuard = new android.widget.FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                final int key = event.getKeyCode();
                if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                            && popup.isShowing()) {
                        popup.dismiss();
                    }
                    // Consume BOTH down and up: never pass Back to the Home activity.
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        // PopupWindow has its own focused window; Activity.dispatchKeyEvent alone
        // cannot see the TV remote's Back press while this popup is focused.
        backGuard.addView(panel, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        popup.setContentView(backGuard);
        popup.setFocusable(true);
    }

    // ROWA_V15: a restrained appearance menu, navigable entirely by D-pad.
    private static final int ROWA_V15_WALLPAPER_PICK = 6125;

    private void rowaV15WallpaperRows(LinearLayout rows, final PopupWindow popup) {
        addSettingsMenuRow(rows, popup, R.string.rowa_v156_default_wallpaper_style, () ->
                RowaWallpaperStyleDialog.show(this, getString(R.string.rowa_v156_default_wallpaper_style),
                        RowaWallpaperStyle.defaults(this),
                        updated -> { RowaWallpaperStyle.setDefaults(this, updated); recreate(); }));
        // Source folder is selected inside Choose background images; do not duplicate that action here.
        // Built-in single-column gallery and in-panel source selection. Selection REPLACES the collection.
        addSettingsMenuRow(rows, popup, R.string.rowa_v15_add_wallpaper,
                () -> startActivityForResult(new Intent(this, RowaDownloadsBrowserActivity.class),
                        ROWA_V15_WALLPAPER_PICK));
        // Preserve the existing PC/ADB import workflow, but keep the menu uncluttered
        // unless files were actually staged via Send-Backgrounds.ps1.
        if (RowaVisualOptions.hasTransferredImages(this)) {
            addSettingsMenuRow(rows, popup, R.string.rowa_browser_import_transferred, () ->
                    RowaVisualOptions.importTransferred(this, imported -> {
                        Toast.makeText(this, imported > 0 ?
                                getString(R.string.rowa_v15_wallpaper_success, imported) :
                                getString(R.string.rowa_v15_wallpaper_error), Toast.LENGTH_LONG).show();
                        if (imported > 0) recreate();
                    }));
        }
        addSettingsMenuRow(rows, popup, R.string.rowa_v15_clear_wallpaper, () -> {
            RowaVisualOptions.clearImages(this);
            recreate();
        });
    }

    /** Time-based or every-Home wallpaper rotation, editable directly with the TV remote. */
    private void rowaV15RotationRow(LinearLayout rows) {
        LinearLayout row = (LinearLayout) getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setTag("rowaRotation");
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView title = row.findViewById(R.id.title);
        title.setText(R.string.rowa_rotation_setting);
        TextView value = row.findViewById(R.id.status);
        value.setVisibility(View.VISIBLE);
        value.setDuplicateParentStateEnabled(true);
        value.setText(RowaWallpaperRotation.label(this));
        java.util.function.IntConsumer adjust = step -> {
            RowaWallpaperRotation.change(this, step);
            value.setText(RowaWallpaperRotation.label(this));
            row.setContentDescription(getString(R.string.rowa_rotation_setting) + ": "
                    + RowaWallpaperRotation.label(this));
            wallpaperPlayer.onRotationSettingChanged();
        };
        row.setContentDescription(getString(R.string.rowa_rotation_setting) + ": "
                + RowaWallpaperRotation.label(this));
        row.setOnClickListener(v -> adjust.accept(1));
        row.setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                adjust.accept(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        });
        rows.addView(row);
    }

    private void rowaV15OpacityRow(LinearLayout rows, View panel) {
        LinearLayout row = (LinearLayout) getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setTag("rowaOpacity");
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView title = row.findViewById(R.id.title);
        title.setText(R.string.rowa_v15_opacity);
        TextView status = row.findViewById(R.id.status);
        int value = RowaVisualOptions.panelOpacity(this);
        status.setText(value + "%");
        status.setVisibility(View.VISIBLE);
        status.setDuplicateParentStateEnabled(true);
        java.util.function.IntConsumer adjust = (amount) -> {
            int before = RowaVisualOptions.panelOpacity(this);
            int next = Math.max(35, Math.min(95, before + amount));
            if (next == before) return;
            RowaVisualOptions.setPanelOpacity(this, next);
            status.setText(next + "%");
            rowaV15ApplyPanelAlpha(panel);
            RowaAppearance.applyHeader(this);
        };
        row.setOnClickListener(v -> adjust.accept(10));
        row.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT)
                return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN &&
                    (event.getRepeatCount() == 0 || event.getRepeatCount() % 3 == 0))
                adjust.accept(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : -10);
            return true;
        });
        rows.addView(row);
    }

    private void rowaV15ApplyPanelAlpha(View panel) {
        if (panel != null && panel.getBackground() != null)
            panel.getBackground().mutate().setAlpha(RowaVisualOptions.panelAlpha(this));
    }

    /** Follow the row focus explicitly: nested value/dot labels do not inherit it on every TV. */
    private void rowaV15Contrast(LinearLayout rows) {
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (!(row instanceof ViewGroup) || !row.isFocusable()) continue;
            View.OnFocusChangeListener previous = row.getOnFocusChangeListener();
            row.setOnFocusChangeListener((v, focused) -> {
                if (previous != null) previous.onFocusChange(v, focused);
                rowaV15ContrastTexts(v, focused);
            });
            rowaV15ContrastTexts(row, row.isFocused());
        }
    }
    private void rowaV15ContrastTexts(View view, boolean focused) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(focused ? 0xFF111111 : 0xFFFFFFFF);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                rowaV15ContrastTexts(group.getChildAt(i), focused);
        }
    }

    private void rowaShowAppearanceMenu() {
        rowaShowAppearanceMenu("buttons");
    }

    private void rowaShowAppearanceMenu(String selectedKey) {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, Math.round(380 * density));
        rowaEnableAppearanceBack(popup, panel);

        rowaAppearanceOption(rows, "buttons", R.string.rowa_top_buttons);
        rowaAppearanceOption(rows, "clock", R.string.rowa_clock_size);
        rowaAppearanceOption(rows, "weather", R.string.rowa_weather_size);
        rowaAppearanceOption(rows, "cards", R.string.rowa_card_size);
        rowaMarginSlider(rows);
        rowaWeatherLocationRow(rows);
        rowaGreetingNameRow(rows);
        rowaV15OpacityRow(rows, panel);
        rowaV15RotationRow(rows);
        rowaV15WallpaperRows(rows, popup);
        rowaAppearanceOption(rows, "glow", R.string.rowa_glow_strength);
        // A small live sample makes glow changes visible even while the menu has focus
        // and the focused app tile behind it is no longer highlighted.
        android.widget.FrameLayout sample = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams sampleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(62 * density));
        sample.setLayoutParams(sampleLp);
        rowaGlowPreview = new GlassView(this, null);
        rowaGlowPreview.setGlowLevel(RowaAppearance.get(this, "glow"));
        android.widget.FrameLayout.LayoutParams previewLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(56 * density));
        previewLp.setMargins(Math.round(44 * density), 0, Math.round(44 * density), 0);
        sample.addView(rowaGlowPreview, previewLp);
        TextView sampleLabel = new TextView(this);
        sampleLabel.setText(R.string.rowa_glow_preview);
        sampleLabel.setTextColor(0xE6FFFFFF);
        sampleLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        sampleLabel.setGravity(Gravity.CENTER);
        sample.addView(sampleLabel, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(56 * density)));
        rows.addView(sample);
        rowaAppearanceOption(rows, "layout", R.string.rowa_layout_mode);
        rowaAppearanceOption(rows, "columns", R.string.rowa_columns);

        // Re-use exactly the launcher Settings dropdown's focus/OK behavior for reset.
        addSettingsMenuRow(rows, popup, R.string.rowa_reset_appearance, () -> {
            RowaAppearance.reset(this);
            rowaAppearanceReopenKey = "buttons";
            recreate();
        });

        rowaFixAppearanceContrast(rows);
        rowaV15Contrast(rows);
        // Same settings glass; scroll rather than showing an off-screen menu on a 32-inch TV.
        // popup_inputs now provides the shared scroll container for every dropdown.
        android.widget.ScrollView scroll = panel.findViewById(R.id.popupScroll);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.round(360 * density)));
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);

        View focus = null;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (selectedKey != null && selectedKey.equals(row.getTag())) {
                focus = row;
                break;
            }
        }
        if (focus == null) focus = rows.getChildAt(0);
        final View selected = focus;
        selected.requestFocus();
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, selected.getTop() - Math.round(36 * density))));
    }

    private static int rowaAppearanceChoices(String key) {
        return "layout".equals(key) ? 5
                : "columns".equals(key) || "glow".equals(key) ? 4 : 3;
    }

    private String rowaAppearanceValue(String key) {
        switch (key) {
            case "margins": return RowaAppearance.marginsName(this);
            case "layout": return RowaAppearance.layoutName(this);
            case "columns": return RowaAppearance.columnsName(this);
            case "glow":
                switch (RowaAppearance.get(this, "glow")) {
                    case 1: return getString(R.string.rowa_glow_bright);
                    case 2: return getString(R.string.rowa_glow_strong);
                    case 3: return getString(R.string.rowa_glow_max);
                    default: return getString(R.string.rowa_size_normal);
                }
            default: return RowaAppearance.sizeName(this, key);
        }
    }

    private String rowaAppearanceDots(String key) {
        StringBuilder dots = new StringBuilder();
        for (int i = 0, n = rowaAppearanceChoices(key); i < n; i++) {
            if (i > 0) dots.append("  ");
            dots.append(i == RowaAppearance.get(this, key) ? '\u25CF' : '\u25CB');
        }
        return dots.toString();
    }

    private void rowaAppearanceStep(String key, int direction, TextView value, TextView dots) {
        int count = rowaAppearanceChoices(key);
        int next = (RowaAppearance.get(this, key) + direction + count) % count;
        RowaAppearance.set(this, key, next);
        value.setText(rowaAppearanceValue(key));
        dots.setText(rowaAppearanceDots(key));
        if ("buttons".equals(key) || "clock".equals(key) || "weather".equals(key)
                || "glow".equals(key) || "margins".equals(key)) {
            // Immediate preview: no Activity recreation, focus or popup loss.
            RowaAppearance.applyHeader(this);
            if (rowaGlowPreview != null) rowaGlowPreview.setGlowLevel(RowaAppearance.get(this, "glow"));
        } else {
            // Column count, grid mode and adapter cell sizes require rebuilding the grid.
            // Reopen automatically on the same row; the user never needs to find Settings again.
            rowaAppearanceReopenKey = key;
            recreate();
        }
    }

    private void rowaAppearanceOption(LinearLayout rows, String key, int labelRes) {
        LinearLayout row = (LinearLayout) getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setTag(key);
        ViewGroup.LayoutParams rowLp = row.getLayoutParams();
        rowLp.height = Math.round(62 * density);
        row.setLayoutParams(rowLp);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView title = row.findViewById(R.id.title);
        String titleText = getString(labelRes, "");
        title.setText(titleText.replaceAll("[:\\s]+$", ""));
        // The existing Settings rows already localize these labels with the normal resources.
        TextView oldStatus = row.findViewById(R.id.status);
        row.removeView(oldStatus);
        LinearLayout values = new LinearLayout(this);
        values.setOrientation(LinearLayout.VERTICAL);
        values.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                Math.round(112 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        values.setLayoutParams(valLp);
        TextView value = new TextView(this);
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        value.setTextColor(getResources().getColorStateList(R.drawable.pill_text, getTheme()));
        value.setDuplicateParentStateEnabled(true);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setText(rowaAppearanceValue(key));
        values.addView(value);
        TextView dots = new TextView(this);
        dots.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
        dots.setGravity(Gravity.CENTER);
        dots.setTextColor(getResources().getColorStateList(R.drawable.pill_text, getTheme()));
        dots.setDuplicateParentStateEnabled(true);
        dots.setText(rowaAppearanceDots(key));
        values.addView(dots);
        row.addView(values);
        row.setContentDescription(titleText + " " + rowaAppearanceValue(key));
        // Allow ordinary OK to move forward; Right/Left steps without closing the menu.
        row.setOnClickListener(v -> rowaAppearanceStep(key, +1, value, dots));
        final boolean[] pressStarted = {false};
        row.setOnKeyListener((v, code, event) -> {
            boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
            boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT;
            if (right || left) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    rowaAppearanceStep(key, right ? +1 : -1, value, dots);
                    row.setContentDescription(titleText + " " + rowaAppearanceValue(key));
                }
                return true;
            }
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressStarted[0] = true;
                return true;
            }
            boolean activate = pressStarted[0];
            pressStarted[0] = false;
            if (activate) rowaAppearanceStep(key, +1, value, dots);
            return true;
        });
        RowaMenuMarquee.bind(row, title, value, dots);
        rows.addView(row);
    }

    // ROWA_UI_V7: weather shares the clock's baseline; preserve the complete date on narrow TVs.
    // If the full clock + date + full-size weather cannot coexist with the top pills,
    // move those pills to a second right-aligned header row rather than truncating the date.
    /** ROWA_UI_V8: weather stays immediately after the date, with clock typography.
     * Do not distribute the date and weather across a weighted header region.
     * Keep the top buttons separate when the display is too narrow for one row.
     */
    /** ROWA_UI_V9: keep one header row, match weather glyph and temperature typography
     * to the clock, and keep the unchanged two-line date entirely inside clock height.
     * This only adjusts Home's header; it does not modify any key or service handling.
     */
    void rowaPolishHeader() {
        TextView weather = findViewById(R.id.rowaWeather);
        TextView time = findViewById(R.id.clock);
        TextView date = findViewById(R.id.date);
        TextView greeting = findViewById(R.id.greeting);
        View divider = findViewById(R.id.clockDivider);
        View left = findViewById(R.id.leftBlock);
        View bar = findViewById(R.id.topButtons);
        if (weather == null || time == null || date == null || greeting == null
                || divider == null || left == null || bar == null) return;
        RowaWeatherVisuals.matchTemperatureHeight(this, weather, time);
        if (RowaResponsiveWeather.update(this, weather, time, date, divider, left)) return;
        if (!(weather.getParent() instanceof LinearLayout)
                || !(date.getParent() instanceof LinearLayout)) return;
        LinearLayout trailing = (LinearLayout) weather.getParent();
        LinearLayout dateColumn = (LinearLayout) date.getParent();
        if (dateColumn.getParent() != trailing || divider.getParent() != trailing
                || !(trailing.getParent() instanceof LinearLayout)) return;
        LinearLayout clockRow = (LinearLayout) trailing.getParent();

        // Use exactly the clock's font object and clock's measured text size.
        // Weather's Small/Large setting is a multiplier, not a separate base size.
        weather.setTypeface(time.getTypeface());
        weather.setTextColor(time.getCurrentTextColor());
        weather.setTextScaleX(time.getTextScaleX());
        weather.setLetterSpacing(time.getLetterSpacing());
        weather.setIncludeFontPadding(time.getIncludeFontPadding());
        weather.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                52f * getResources().getDisplayMetrics().scaledDensity
                        * RowaAppearance.scale(this, "weather"));
        weather.setSingleLine(true);
        weather.setBackground(null);
        weather.setPadding(0, 0, 0, 0);
        weather.setGravity(Gravity.CENTER_VERTICAL);

        // Date: font family and both date lines stay completely untouched.
        // Only the text size follows the greeting above the clock.
        date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, greeting.getTextSize());
        date.setMaxWidth(Math.round(155f * density));
        // Do not change date's singleLine/maxLines/format/text/lineSpacing/typeface.
        date.setEllipsize(null); // Free the existing second date line from "K...".

        // The old width=0, weight=1 rules stretched the gap before the weather.
        LinearLayout.LayoutParams trailingLp = (LinearLayout.LayoutParams) trailing.getLayoutParams();
        if (trailingLp.width != ViewGroup.LayoutParams.WRAP_CONTENT || trailingLp.weight != 0f) {
            trailingLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            trailingLp.weight = 0f;
            trailing.setLayoutParams(trailingLp);
        }
        LinearLayout.LayoutParams dateLp = (LinearLayout.LayoutParams) dateColumn.getLayoutParams();
        if (dateLp.width != ViewGroup.LayoutParams.WRAP_CONTENT || dateLp.weight != 0f) {
            dateLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            dateLp.weight = 0f;
            dateColumn.setLayoutParams(dateLp);
        }
        ViewGroup.LayoutParams dateTextLp = date.getLayoutParams();
        if (dateTextLp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            dateTextLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            date.setLayoutParams(dateTextLp);
        }
        // A full-size weather item and the smaller two-line date are centred
        // within the clock's text row. The date no longer protrudes above/below.
        clockRow.setGravity(verticalRail ? Gravity.RIGHT | Gravity.CENTER_VERTICAL : Gravity.CENTER_VERTICAL);
        trailing.setGravity(Gravity.CENTER_VERTICAL);
        dateColumn.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams weatherLp = (LinearLayout.LayoutParams) weather.getLayoutParams();
        if (weatherLp.topMargin != 0 || weatherLp.bottomMargin != 0
                || weatherLp.leftMargin != 0 || weatherLp.rightMargin != 0) {
            weatherLp.setMargins(0, 0, 0, 0);
            weather.setLayoutParams(weatherLp);
        }
        LinearLayout.LayoutParams dividerLp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        int dividerWidth = Math.max(1, Math.round(density));
        int dividerHeight = Math.round(27f * density);
        int dividerGap = Math.round(8f * density);
        if (dividerLp.width != dividerWidth || dividerLp.height != dividerHeight
                || dividerLp.leftMargin != dividerGap || dividerLp.rightMargin != dividerGap
                || dividerLp.topMargin != 0 || dividerLp.bottomMargin != 0) {
            dividerLp.width = dividerWidth;
            dividerLp.height = dividerHeight;
            dividerLp.setMargins(dividerGap, 0, dividerGap, 0);
            divider.setLayoutParams(dividerLp);
        }
        divider.setVisibility(weather.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);

        // Vertical mode deliberately places leftBlock BELOW topButtons. Keep the
        // clock/weather typography work above, but never reapply the old horizontal
        // RelativeLayout constraints or the delayed top-bar alignment below.
        if (verticalRail) return;

        // v7/v8 moved the whole buttons group below the header on tight screens.
        // Never do that: restore the original, shared top header row every time.
        if (bar.getParent() instanceof RelativeLayout
                && left.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams leftLp = (RelativeLayout.LayoutParams) left.getLayoutParams();
            if (leftLp.getRule(RelativeLayout.START_OF) != R.id.topButtons) {
                leftLp.addRule(RelativeLayout.START_OF, R.id.topButtons);
                left.setLayoutParams(leftLp);
            }
            RelativeLayout.LayoutParams barLp = (RelativeLayout.LayoutParams) bar.getLayoutParams();
            boolean moved = barLp.getRule(RelativeLayout.BELOW) != 0
                    || barLp.getRule(RelativeLayout.ALIGN_BOTTOM) != 0
                    || barLp.getRule(RelativeLayout.ALIGN_TOP) != R.id.leftBlock;
            if (moved) {
                barLp.removeRule(RelativeLayout.BELOW);
                barLp.removeRule(RelativeLayout.ALIGN_BOTTOM);
                barLp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
                barLp.topMargin = Math.round(21f * density);
                bar.setLayoutParams(barLp);
            }
        }

        // Position top buttons at the vertical centre of the clock, not below it.
        // The 1-pixel guard prevents a layout-invalidation loop on inexpensive TVs.
        clockRow.post(() -> {
            if (!bar.isAttachedToWindow() || time.getHeight() == 0 || bar.getHeight() == 0) return;
            RelativeLayout.LayoutParams barLp = (RelativeLayout.LayoutParams) bar.getLayoutParams();
            int wanted = Math.max(0, clockRow.getTop()
                    + (time.getHeight() - bar.getHeight()) / 2);
            if (Math.abs(barLp.topMargin - wanted) > 1) {
                barLp.topMargin = wanted;
                bar.setLayoutParams(barLp);
            }
            // Make the weather baseline agree with the clock even when an emoji
            // is rendered by a fallback font. Reset translation before measuring,
            // otherwise repeated weather/network refreshes accumulate offsets.
            weather.setTranslationY(0f);
            if (weather.getVisibility() != View.VISIBLE || time.getBaseline() < 0
                    || weather.getBaseline() < 0) return;
            int[] txy = new int[2], wxy = new int[2];
            time.getLocationInWindow(txy);
            weather.getLocationInWindow(wxy);
            float correction = (txy[1] + time.getBaseline())
                    - (wxy[1] + weather.getBaseline());
            if (Math.abs(correction) > 0.5f && Math.abs(correction) < 55f * density)
                weather.setTranslationY(correction);
        });
    }

    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        addSettingsMenuRow(rows, popup, R.string.rowa_appearance,
                () -> root.post(this::rowaShowAppearanceMenu));
        if (!launcherSettingsButton) addSettingsMenuRow(rows, popup, R.string.launcher_settings, () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        // Firmware that ships its own settings app next to the AOSP one (Xiaomi China is one): the
        // vendor app holds pages the AOSP settings has no row for, such as Account & Security with
        // the ADB switch. Offered here only while one of them is installed.
        if (SystemSettings.vendorIntent(this) != null) {
            addSettingsMenuRow(rows, popup, R.string.native_settings, () -> {
                Intent vendor = SystemSettings.vendorIntent(this);
                if (vendor != null) open(vendor);
            });
        }
        if (moduleCards) addSettingsMenuRow(rows, popup, R.string.module_cards, this::showModuleCards);
        if (dockRow) addSettingsMenuRow(rows, popup, R.string.dock_edit, this::startDockEdit);
        addSettingsMenuRow(rows, popup, R.string.reorder, this::toggleReorder);
        addSettingsMenuRow(rows, popup, R.string.app_appearance_edit,
                () -> startActivity(new Intent(this, AppAppearanceActivity.class)));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /**
     * Hold OK on a tile: a glass menu next to it. Move starts reorder mode with this tile already picked up;
     * Remove from Home takes the shortcut off (only with the app library on, where one press adds it back);
     * App info opens the TV's page for the app, which is what holding OK used to do.
     */
    @Override
    public void onTileLongPress(int position, View card) {
        if (reordering) return;
        String key = adapter.keyAt(position);
        String pkg = adapter.packageAt(position);
        // Empty dock slots and the Default-home tile carry the launcher's own package: no menu for them.
        if (key == null || pkg == null || pkg.equals(getPackageName())) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        if (dockRow && position < adapter.movableCount()) {
            boolean inDock = position < COLUMNS;
            addSettingsMenuRow(rows, popup, inDock ? R.string.tile_replace_in_dock : R.string.tile_add_to_dock,
                    () -> startDockEditFor(key, inDock));
        }
        if (position < adapter.movableCount()) addSettingsMenuRow(rows, popup, R.string.tile_move, () -> {
            if (!beginReorder()) return;
            int at = adapter.positionOf(key);   // beginReorder can rebuild the tiles
            if (at < 0) return;
            focusTile(at);
            pickUp(at);
            Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
        });
        if (appLibraryEnabled) addSettingsMenuRow(rows, popup, R.string.library_remove_shortcut, () -> {
            if (LibraryApps.save(this, pkg, false)) reloadApps(false);
            else Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_LONG).show();
        });
        // Some firmware has no App info page at all; a row that only toasts "can't open" helps nobody.
        Intent info = SystemSettings.appInfo(this, pkg);
        if (info != null) addSettingsMenuRow(rows, popup, R.string.library_app_info, () -> open(info));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Dock tiles sit near the bottom of the screen: open the menu above the tile when it would not fit below.
        int[] at = new int[2];
        card.getLocationInWindow(at);
        int gap = (int) (10 * density), height = panel.getMeasuredHeight();
        if (at[1] + card.getHeight() + gap + height <= root.getHeight()) {
            dropIn(popup, panel, card, Gravity.START);
        } else {
            panel.setAlpha(0f);
            panel.setPivotY(height);
            panel.setScaleY(0.92f);
            panel.setTranslationY(8 * density);
            popup.showAtLocation(root, Gravity.NO_GRAVITY, at[0], Math.max(0, at[1] - gap - height));
            panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        // The same D-pad guards, marquee, and glass-row layout used by dynamic labels.
        rowaAddSettingsStyleRow(rows, popup, getString(title), action);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()
                || reordering) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        RowaMenuStyle.glass(panel, this);
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        if (w != null) w.setLayout(Math.min(Math.round(440 * density),
                getResources().getDisplayMetrics().widthPixels - Math.round(32 * density)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        // Hebrew places Settings on the left: align the outer panel inward, like its parent menu.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
