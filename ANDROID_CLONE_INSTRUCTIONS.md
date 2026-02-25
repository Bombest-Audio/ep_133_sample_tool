# EP-133 Sample Tool - Android Clone Development Guide
### Leveraging Android 16 Connected Displays Support

## Project Overview

This document provides comprehensive instructions for creating an Android clone of the EP-133 Sample Tool, specifically designed to take advantage of **Android 16's groundbreaking connected displays support**. The original application is an Electron desktop app that communicates with the Teenage Engineering EP-133 sampler via Web MIDI API for sample management, backup/restore operations, and project management.

## Why Android 16 Connected Displays Are Perfect for This Project

Android 16's connected displays feature transforms mobile devices into desktop-class workstations - exactly what's needed for professional audio production tools like the EP-133 Sample Tool:

### Key Benefits for Audio Production:
- **Desktop-Class Experience**: When connected to an external monitor, your phone becomes a full desktop session with taskbar, resizable windows, and desktop windowing
- **Multi-Instance Support**: Run multiple instances of the EP-133 tool simultaneously for complex projects
- **Professional Workflow**: External keyboard, mouse, and audio interface support for studio-grade workflows  
- **Flexible Window Management**: Resize, tile, and organize windows like a traditional DAW
- **Multiple Desktop Sessions**: Separate your mobile experience from your production workspace
- **Extended Desktop Mode**: On tablets, the desktop session extends across both displays for maximum workspace

## Understanding the Original Application

### Core Functionality
- **MIDI Communication**: Uses Web MIDI API to communicate with EP-133 via USB
- **Sample Management**: Upload, download, and organize audio samples
- **Project Backup/Restore**: Full device backup or project-only backup
- **Audio Processing**: Uses WebAssembly modules for audio processing (libsndfile, libsamplerate, etc.)
- **File Format Support**: Handles `.pak` files (EP-133 sound packs) and `.hmls` files
- **Custom UI**: React-based interface with zoom capabilities and custom theming

### Technical Stack (Original)
- **Frontend**: React application
- **Backend**: Electron main process
- **MIDI**: Web MIDI API
- **Audio Processing**: WebAssembly (libsndfile.wasm, libsamplerate.wasm, etc.)
- **Build System**: Electron Builder

## Android Development Strategy

Since Android doesn't support Web MIDI API natively, we'll need to use alternative approaches for MIDI communication and adapt the web-based architecture to Android.

## Phase 1: Development Environment Setup

### Prerequisites

1. **Install Android Studio (Latest Version)**
   ```bash
   # Download from https://developer.android.com/studio
   # Ensure Android SDK API 34+ (Android 14) for full Android 16 features
   # Install Android 16 QPR1 Beta 2 or later
   ```

2. **Install Java Development Kit (JDK)**
   ```bash
   # Install JDK 17 or higher (required for Android 16 features)
   brew install openjdk@17  # macOS
   ```

3. **Setup Android 16 Connected Displays Testing**
   ```bash
   # Get Android 16 QPR1 Beta 2 on supported Pixel device (Pixel 8/9 series)
   # Enable desktop experience features in developer settings
   # Note: Android Emulator support for connected displays coming soon
   ```

4. **Setup Git and Clone Repository**
   ```bash
   git clone https://github.com/garrettjwilke/ep_133_sample_tool.git
   cd ep_133_sample_tool
   git checkout android-clone-instructions
   ```

4. **Install Node.js and Dependencies** (for examining original code)
   ```bash
   npm install
   ```

### Android Project Structure
```
EP133SampleTool/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/ep133/sampletool/
│   │   │   │   ├── MainActivity.java
│   │   │   │   ├── ConnectedDisplayManager.java    # NEW: Android 16 display management
│   │   │   │   ├── DesktopWindowingManager.java    # NEW: Window management
│   │   │   │   ├── MultiInstanceManager.java       # NEW: Multi-instance support
│   │   │   │   ├── MidiManager.java
│   │   │   │   ├── SampleManager.java
│   │   │   │   ├── BackupManager.java
│   │   │   │   └── AudioProcessor.java
│   │   │   ├── assets/
│   │   │   │   ├── web/
│   │   │   │   │   ├── index.html
│   │   │   │   │   ├── desktop-layout.html         # NEW: Desktop-optimized layout
│   │   │   │   │   ├── mobile-layout.html          # NEW: Mobile-optimized layout
│   │   │   │   │   ├── js/
│   │   │   │   │   ├── css/
│   │   │   │   │   └── wasm/
│   │   │   │   └── sounds/
│   │   │   └── res/
│   │   │   │   ├── layout-land/                    # NEW: Landscape layouts
│   │   │   │   ├── layout-w600dp/                  # NEW: Large screen layouts
│   │   │   │   └── layout-sw720dp/                 # NEW: Tablet layouts
│   │   └── androidTest/
│   ├── build.gradle
│   └── proguard-rules.pro
├── gradle/
├── build.gradle
└── settings.gradle
```

## Phase 2: Core Android Project Setup

### Step 1: Create New Android Project

1. Open Android Studio
2. Create new project with the following settings:
   - **Name**: EP-133 Sample Tool
   - **Package**: com.ep133.sampletool
   - **Language**: Java
   - **Minimum SDK**: API 34 (Android 14) - Required for Android 16 connected displays preview
   - **Target SDK**: API 35 (Android 16) - For full connected displays support
   - **Activity**: Empty Activity

### Step 2: Configure Dependencies for Android 16 Features

Add to `app/build.gradle`:
```gradle
android {
    compileSdk 35  // Android 16
    
    defaultConfig {
        applicationId "com.ep133.sampletool"
        minSdk 34      // Android 14 minimum for connected displays
        targetSdk 35   // Android 16 for latest features
        versionCode 1
        versionName "1.0"
        
        // Enable connected displays and desktop windowing
        resConfigs "en", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"
    }
    
    // Support for connected displays and desktop windowing
    buildFeatures {
        compose true
        dataBinding true
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    
    // Android 16 Connected Displays & Desktop Windowing
    implementation 'androidx.window:window:1.3.0'
    implementation 'androidx.window:window-core:1.3.0'
    implementation 'androidx.activity:activity:1.9.0'
    
    // Adaptive Layout Support
    implementation 'androidx.compose.material3:material3-adaptive:1.0.0'
    implementation 'androidx.compose.material3:material3-adaptive-navigation-suite:1.0.0'
    
    // WebView for UI
    implementation 'androidx.webkit:webkit:1.10.0'
    
    // USB Host API
    implementation 'androidx.core:core:1.13.0'
    
    // File handling
    implementation 'commons-io:commons-io:2.11.0'
    
    // JSON processing
    implementation 'com.google.code.gson:gson:2.10.1'
    
    // Audio processing
    implementation 'androidx.media:media:1.7.0'
    
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
```

### Step 3: Configure Permissions and Features

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.USB_PERMISSION" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.INTERNET" />

<!-- Connected Displays and Desktop Windowing Features -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<uses-feature
    android:name="android.hardware.usb.host"
    android:required="true" />

<!-- Desktop windowing and connected displays support -->
<uses-feature
    android:name="android.software.freeform_window_management"
    android:required="false" />

<!-- Multi-instance support -->
<property 
    android:name="android.window.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI"
    android:value="true" />

<application
    android:name=".EP133Application"
    android:allowBackup="true"
    android:theme="@style/Theme.EP133SampleTool"
    android:resizeableActivity="true"
    android:supportsPictureInPicture="false">
    
    <activity
        android:name=".MainActivity"
        android:exported="true"
        android:resizeableActivity="true"
        android:supportsPictureInPicture="false"
        android:configChanges="density|orientation|screenSize|screenLayout|keyboardHidden|navigation"
        android:launchMode="singleTop">
        
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
        
        <!-- Support for launching in desktop windowing -->
        <intent-filter>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
        </intent-filter>
    </activity>
</application>
```

## Phase 3: Android 16 Connected Displays Implementation

### Overview
This is the game-changing feature that makes this EP-133 tool truly professional. Android 16's connected displays support allows users to connect their phone to an external monitor and get a full desktop experience with the EP-133 Sample Tool.

### Key Features to Implement:
1. **Automatic Display Detection**: Detect when external monitors are connected
2. **Adaptive Layout Switching**: Switch between mobile and desktop UI layouts  
3. **Multi-Instance Support**: Allow multiple instances for complex workflows
4. **Desktop Windowing Integration**: Proper window management and resizing
5. **External Peripheral Support**: Keyboard shortcuts, mouse interactions, etc.

### Step 1: Create Connected Display Manager

Create `ConnectedDisplayManager.java`:
```java
public class ConnectedDisplayManager {
    private static final String TAG = "ConnectedDisplayManager";
    private Context context;
    private DisplayManager displayManager;
    private Display[] displays;
    private ConnectedDisplayCallback callback;
    
    public interface ConnectedDisplayCallback {
        void onExternalDisplayConnected(Display display);
        void onExternalDisplayDisconnected();
        void onDisplayChanged(Display display);
        void onDesktopModeEnabled();
        void onDesktopModeDisabled();
    }
    
    public ConnectedDisplayManager(Context context, ConnectedDisplayCallback callback) {
        this.context = context;
        this.callback = callback;
        this.displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        setupDisplayListener();
    }
    
    private void setupDisplayListener() {
        displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                Log.d(TAG, "Display added: " + displayId);
                Display display = displayManager.getDisplay(displayId);
                if (display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    callback.onExternalDisplayConnected(display);
                    // Check if this enables desktop mode
                    if (isDesktopModeAvailable()) {
                        callback.onDesktopModeEnabled();
                    }
                }
            }
            
            @Override
            public void onDisplayRemoved(int displayId) {
                Log.d(TAG, "Display removed: " + displayId);
                callback.onExternalDisplayDisconnected();
                if (!isDesktopModeAvailable()) {
                    callback.onDesktopModeDisabled();
                }
            }
            
            @Override
            public void onDisplayChanged(int displayId) {
                Log.d(TAG, "Display changed: " + displayId);
                Display display = displayManager.getDisplay(displayId);
                if (display != null) {
                    callback.onDisplayChanged(display);
                }
            }
        }, null);
    }
    
    public boolean isDesktopModeAvailable() {
        Display[] displays = displayManager.getDisplays();
        return displays.length > 1; // More than just the default display
    }
    
    public Display[] getAllDisplays() {
        return displayManager.getDisplays();
    }
    
    public Display getExternalDisplay() {
        Display[] displays = displayManager.getDisplays();
        for (Display display : displays) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                return display;
            }
        }
        return null;
    }
    
    public DisplayMetrics getDisplayMetrics(Display display) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        return metrics;
    }
    
    public boolean isDisplayHighDensity(Display display) {
        DisplayMetrics metrics = getDisplayMetrics(display);
        return metrics.densityDpi >= DisplayMetrics.DENSITY_XHIGH;
    }
}
```

### Step 2: Create Desktop Windowing Manager

Create `DesktopWindowingManager.java`:
```java
public class DesktopWindowingManager {
    private static final String TAG = "DesktopWindowingManager";
    private Activity activity;
    private boolean isInDesktopMode = false;
    private WindowManager windowManager;
    
    public interface DesktopWindowingCallback {
        void onWindowResized(int width, int height);
        void onFullScreenRequested();
        void onWindowModeChanged(boolean isDesktopMode);
    }
    
    private DesktopWindowingCallback callback;
    
    public DesktopWindowingManager(Activity activity, DesktopWindowingCallback callback) {
        this.activity = activity;
        this.callback = callback;
        this.windowManager = activity.getWindowManager();
        setupWindowListener();
    }
    
    private void setupWindowListener() {
        // Listen for window size changes
        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    checkWindowMode();
                }
            }
        );
    }
    
    private void checkWindowMode() {
        // Check if we're in desktop windowing mode
        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        
        // Check window bounds and compare to display size
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        display.getMetrics(displayMetrics);
        
        View decorView = activity.getWindow().getDecorView();
        int windowWidth = decorView.getWidth();
        int windowHeight = decorView.getHeight();
        
        // Determine if we're in a resizable window (desktop mode)
        boolean wasInDesktopMode = isInDesktopMode;
        isInDesktopMode = (windowWidth < displayMetrics.widthPixels * 0.9f) || 
                         (windowHeight < displayMetrics.heightPixels * 0.9f);
        
        if (wasInDesktopMode != isInDesktopMode) {
            callback.onWindowModeChanged(isInDesktopMode);
        }
        
        callback.onWindowResized(windowWidth, windowHeight);
    }
    
    public void enableCustomHeaderBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                // Make caption bar transparent for custom header
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
                );
            }
        }
    }
    
    public void setDefaultWindowSize(int width, int height) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchBounds(new Rect(100, 100, width + 100, height + 100));
            // Store for next launch
        }
    }
    
    public void requestFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.requestFullScreenMode();
        } else {
            // Fallback for older versions
            activity.getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
        }
    }
    
    public boolean isInDesktopMode() {
        return isInDesktopMode;
    }
    
    public void setSystemGestureExclusionRects(List<Rect> rects) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getWindow().getDecorView().setSystemGestureExclusionRects(rects);
        }
    }
}
```

### Step 3: Create Multi-Instance Manager

Create `MultiInstanceManager.java`:
```java
public class MultiInstanceManager {
    private static final String TAG = "MultiInstanceManager";
    private Context context;
    private static int instanceCount = 0;
    private int currentInstanceId;
    
    public static final String EXTRA_INSTANCE_ID = "instance_id";
    public static final String EXTRA_LAUNCH_DISPLAY_ID = "launch_display_id";
    
    public MultiInstanceManager(Context context) {
        this.context = context;
        this.currentInstanceId = ++instanceCount;
    }
    
    public void enableMultiInstanceSupport() {
        // This is handled in AndroidManifest.xml with:
        // android:window.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI = true
        Log.d(TAG, "Multi-instance support enabled for instance " + currentInstanceId);
    }
    
    public Intent createNewInstanceIntent() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        intent.putExtra(EXTRA_INSTANCE_ID, instanceCount + 1);
        return intent;
    }
    
    public Intent createNewInstanceOnDisplay(int displayId) {
        Intent intent = createNewInstanceIntent();
        intent.putExtra(EXTRA_LAUNCH_DISPLAY_ID, displayId);
        return intent;
    }
    
    public void launchNewInstance() {
        Intent intent = createNewInstanceIntent();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ActivityOptions options = ActivityOptions.makeBasic();
            
            // Launch on external display if available
            ConnectedDisplayManager displayManager = new ConnectedDisplayManager(context, null);
            Display externalDisplay = displayManager.getExternalDisplay();
            if (externalDisplay != null) {
                options.setLaunchDisplayId(externalDisplay.getDisplayId());
            }
            
            context.startActivity(intent, options.toBundle());
        } else {
            context.startActivity(intent);
        }
    }
    
    public void launchNewInstanceOnDisplay(Display display) {
        Intent intent = createNewInstanceIntent();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(display.getDisplayId());
            context.startActivity(intent, options.toBundle());
        }
    }
    
    public int getCurrentInstanceId() {
        return currentInstanceId;
    }
    
    public static int getTotalInstanceCount() {
        return instanceCount;
    }
    
    // Support for drag-and-drop between instances
    public void setupDragAndDropSupport(View sourceView, String contentType) {
        sourceView.setOnLongClickListener(v -> {
            // Create clip data for dragging between instances
            ClipData clipData = ClipData.newPlainText("EP133_Content", contentType);
            
            // Create intent for new instance if drag is unhandled
            Intent newInstanceIntent = createNewInstanceIntent();
            PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, newInstanceIntent, PendingIntent.FLAG_IMMUTABLE);
            
            ClipData.Item intentItem = new ClipData.Item.Builder()
                .setIntentSender(pendingIntent.getIntentSender())
                .build();
            clipData.addItem(intentItem);
            
            View.DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(v);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                v.startDragAndDrop(clipData, shadowBuilder, null, 
                    View.DRAG_FLAG_GLOBAL_SAME_APPLICATION | 
                    View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG);
            }
            
            return true;
        });
    }
}
```

### Step 4: Implement Adaptive Layout System

Create `AdaptiveLayoutManager.java`:
```java
public class AdaptiveLayoutManager {
    private static final String TAG = "AdaptiveLayoutManager";
    private Context context;
    private WebView webView;
    private boolean isDesktopLayout = false;
    
    // Window size classes for responsive design
    public enum WindowSizeClass {
        COMPACT,    // < 600dp width (phones)
        MEDIUM,     // 600-840dp width (tablets, foldables)
        EXPANDED    // > 840dp width (large tablets, desktop displays)
    }
    
    public AdaptiveLayoutManager(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
    }
    
    public WindowSizeClass getCurrentWindowSizeClass() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float widthDp = metrics.widthPixels / metrics.density;
        
        if (widthDp < 600) {
            return WindowSizeClass.COMPACT;
        } else if (widthDp < 840) {
            return WindowSizeClass.MEDIUM;
        } else {
            return WindowSizeClass.EXPANDED;
        }
    }
    
    public void updateLayoutForDisplay(Display display) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        
        float widthDp = metrics.widthPixels / metrics.density;
        float heightDp = metrics.heightPixels / metrics.density;
        
        boolean shouldUseDesktopLayout = widthDp >= 840 || display.getDisplayId() != Display.DEFAULT_DISPLAY;
        
        if (shouldUseDesktopLayout != isDesktopLayout) {
            isDesktopLayout = shouldUseDesktopLayout;
            switchToAppropriateLayout();
        }
        
        // Update CSS variables for responsive design
        updateResponsiveCSS(widthDp, heightDp);
    }
    
    private void switchToAppropriateLayout() {
        String layoutFile = isDesktopLayout ? "desktop-layout.html" : "mobile-layout.html";
        String layoutUrl = "file:///android_asset/web/" + layoutFile;
        
        // Inject layout switching JavaScript
        String jsCode = String.format(
            "if (window.switchLayout) { window.switchLayout('%s'); }",
            isDesktopLayout ? "desktop" : "mobile"
        );
        
        webView.evaluateJavascript(jsCode, result -> {
            Log.d(TAG, "Layout switched to: " + (isDesktopLayout ? "desktop" : "mobile"));
        });
    }
    
    private void updateResponsiveCSS(float widthDp, float heightDp) {
        String cssVariables = String.format(
            "document.documentElement.style.setProperty('--window-width', '%fdp'); " +
            "document.documentElement.style.setProperty('--window-height', '%fdp'); " +
            "document.documentElement.style.setProperty('--is-desktop', '%s');",
            widthDp, heightDp, isDesktopLayout ? "true" : "false"
        );
        
        webView.evaluateJavascript(cssVariables, null);
    }
    
    public void enableKeyboardShortcuts() {
        if (!isDesktopLayout) return;
        
        // Enable common keyboard shortcuts for desktop mode
        String shortcutsJs = 
            "document.addEventListener('keydown', function(e) {" +
            "  if (e.ctrlKey || e.metaKey) {" +
            "    switch(e.key) {" +
            "      case 's': e.preventDefault(); window.Android.saveProject(); break;" +
            "      case 'o': e.preventDefault(); window.Android.openProject(); break;" +
            "      case 'n': e.preventDefault(); window.Android.newInstance(); break;" +
            "      case 'f': e.preventDefault(); window.Android.toggleFullScreen(); break;" +
            "    }" +
            "  }" +
            "});";
        
        webView.evaluateJavascript(shortcutsJs, null);
    }
    
    public boolean isUsingDesktopLayout() {
        return isDesktopLayout;
    }
}
```

## Phase 4: Enhanced MIDI Communication Layer

### USB MIDI Implementation Strategy

Since Android doesn't have Web MIDI API, we'll use USB Host API with MIDI protocol implementation.

### Step 1: Create MIDI Manager Class

Create `MidiManager.java`:
```java
public class MidiManager {
    private static final String TAG = "MidiManager";
    private UsbManager usbManager;
    private UsbDevice ep133Device;
    private UsbDeviceConnection connection;
    private UsbInterface midiInterface;
    private UsbEndpoint inEndpoint;
    private UsbEndpoint outEndpoint;
    
    // MIDI constants
    private static final int EP133_VENDOR_ID = 0x0fbb; // Teenage Engineering
    private static final int EP133_PRODUCT_ID = 0x0133; // EP-133
    
    public interface MidiCallback {
        void onDeviceConnected();
        void onDeviceDisconnected();
        void onDataReceived(byte[] data);
        void onError(String error);
    }
    
    // Implementation methods for:
    // - Device detection and connection
    // - MIDI message sending/receiving
    // - SysEx message handling
    // - Error handling
}
```

### Step 2: Implement SysEx Protocol

Based on the original code analysis, create SysEx message handlers:
```java
public class SysExProtocol {
    // EP-133 specific SysEx commands
    private static final byte[] DEVICE_INQUIRY = {0xF0, 0x7E, 0x00, 0x06, 0x01, 0xF7};
    private static final byte[] BACKUP_REQUEST = {0xF0, 0x00, 0x20, 0x76, ...};
    
    public static byte[] createBackupCommand() {
        // Implement backup SysEx command creation
    }
    
    public static byte[] createSampleUploadCommand(byte[] sampleData) {
        // Implement sample upload SysEx command
    }
    
    // Additional protocol methods...
}
```

## Phase 5: Enhanced Web-Based UI Integration

### Strategy: Adaptive WebView with Connected Display Support

Use WebView with adaptive layouts that automatically switch between mobile and desktop modes based on display configuration.

### Step 1: Create Adaptive Web Assets

1. **Create Mobile Layout** (`mobile-layout.html`):
   ```html
   <!DOCTYPE html>
   <html>
   <head>
       <meta charset="UTF-8">
       <meta name="viewport" content="width=device-width, initial-scale=1.0">
       <title>EP-133 Sample Tool - Mobile</title>
       <link rel="stylesheet" href="mobile.css">
       <link rel="stylesheet" href="index.css">
   </head>
   <body class="mobile-layout">
       <div id="mobile-header">
           <h1>EP-133 Sample Tool</h1>
           <button id="desktop-mode-btn">Connect Display</button>
       </div>
       <div id="root" class="mobile-container"></div>
       <script src="android-bridge.js"></script>
       <script src="adaptive-ui.js"></script>
       <script src="index.js"></script>
   </body>
   </html>
   ```

2. **Create Desktop Layout** (`desktop-layout.html`):
   ```html
   <!DOCTYPE html>
   <html>
   <head>
       <meta charset="UTF-8">
       <meta name="viewport" content="width=device-width, initial-scale=1.0">
       <title>EP-133 Sample Tool - Desktop</title>
       <link rel="stylesheet" href="desktop.css">
       <link rel="stylesheet" href="index.css">
   </head>
   <body class="desktop-layout">
       <div id="desktop-header" class="custom-caption-bar">
           <div class="header-content">
               <h1>EP-133 Sample Tool</h1>
               <div class="instance-controls">
                   <button id="new-instance-btn">New Instance</button>
                   <button id="save-project-btn">Save Project</button>
                   <button id="load-project-btn">Load Project</button>
               </div>
           </div>
       </div>
       <div id="root" class="desktop-container">
           <div id="main-panel" class="resizable-panel"></div>
           <div id="side-panel" class="resizable-panel"></div>
       </div>
       <script src="android-bridge.js"></script>
       <script src="adaptive-ui.js"></script>
       <script src="desktop-features.js"></script>
       <script src="index.js"></script>
   </body>
   </html>
   ```

3. **Create Adaptive UI Controller** (`adaptive-ui.js`):
   ```javascript
   class AdaptiveUIController {
       constructor() {
           this.currentLayout = 'mobile';
           this.windowSizeClass = 'compact';
           this.isConnectedDisplay = false;
           this.setupEventListeners();
       }
       
       setupEventListeners() {
           window.addEventListener('resize', () => this.handleResize());
           
           // Listen for Android display change events
           if (window.Android) {
               window.Android.onDisplayChanged = (displayInfo) => {
                   this.handleDisplayChange(displayInfo);
               };
           }
       }
       
       handleResize() {
           const width = window.innerWidth;
           const height = window.innerHeight;
           
           // Determine window size class
           if (width < 600) {
               this.windowSizeClass = 'compact';
           } else if (width < 840) {
               this.windowSizeClass = 'medium';
           } else {
               this.windowSizeClass = 'expanded';
           }
           
           this.updateUIForSizeClass();
       }
       
       handleDisplayChange(displayInfo) {
           this.isConnectedDisplay = displayInfo.isExternalDisplay;
           const shouldUseDesktop = this.windowSizeClass === 'expanded' || this.isConnectedDisplay;
           
           if (shouldUseDesktop && this.currentLayout !== 'desktop') {
               this.switchToDesktopLayout();
           } else if (!shouldUseDesktop && this.currentLayout !== 'mobile') {
               this.switchToMobileLayout();
           }
       }
       
       switchToDesktopLayout() {
           this.currentLayout = 'desktop';
           document.body.className = 'desktop-layout';
           
           // Enable desktop features
           this.enableDesktopFeatures();
           this.setupKeyboardShortcuts();
           this.enableDragAndDrop();
           
           // Notify Android layer
           if (window.Android) {
               window.Android.onLayoutChanged('desktop');
           }
       }
       
       switchToMobileLayout() {
           this.currentLayout = 'mobile';
           document.body.className = 'mobile-layout';
           
           // Disable desktop-specific features
           this.disableDesktopFeatures();
           
           // Notify Android layer
           if (window.Android) {
               window.Android.onLayoutChanged('mobile');
           }
       }
       
       enableDesktopFeatures() {
           // Enable multi-panel layout
           this.setupResizablePanels();
           
           // Enable custom header bar for desktop windowing
           this.setupCustomHeaderBar();
           
           // Enable context menus
           this.setupContextMenus();
       }
       
       setupKeyboardShortcuts() {
           document.addEventListener('keydown', (e) => {
               if (e.ctrlKey || e.metaKey) {
                   switch(e.key) {
                       case 's':
                           e.preventDefault();
                           this.saveProject();
                           break;
                       case 'o':
                           e.preventDefault();
                           this.openProject();
                           break;
                       case 'n':
                           e.preventDefault();
                           this.createNewInstance();
                           break;
                       case 'f':
                           e.preventDefault();
                           this.toggleFullScreen();
                           break;
                   }
               }
           });
       }
       
       enableDragAndDrop() {
           // Enable drag and drop for samples between instances
           const draggableElements = document.querySelectorAll('.sample-item');
           
           draggableElements.forEach(element => {
               element.setAttribute('draggable', 'true');
               
               element.addEventListener('dragstart', (e) => {
                   const sampleData = element.dataset.sampleData;
                   e.dataTransfer.setData('text/plain', sampleData);
                   e.dataTransfer.setData('application/ep133-sample', sampleData);
               });
           });
           
           // Setup drop zones
           document.addEventListener('drop', (e) => {
               e.preventDefault();
               const sampleData = e.dataTransfer.getData('application/ep133-sample');
               if (sampleData && window.Android) {
                   window.Android.handleSampleDrop(sampleData);
               }
           });
           
           document.addEventListener('dragover', (e) => {
               e.preventDefault();
           });
       }
       
       setupCustomHeaderBar() {
           // Set up custom header bar for desktop windowing
           const headerBar = document.getElementById('desktop-header');
           if (headerBar) {
               headerBar.style.paddingTop = 'env(titlebar-area-height, 32px)';
               
               // Make header bar draggable for window management
               headerBar.style.webkitAppRegion = 'drag';
               
               // But keep buttons interactive
               const buttons = headerBar.querySelectorAll('button');
               buttons.forEach(button => {
                   button.style.webkitAppRegion = 'no-drag';
               });
           }
       }
       
       createNewInstance() {
           if (window.Android && window.Android.createNewInstance) {
               window.Android.createNewInstance();
           }
       }
       
       saveProject() {
           if (window.Android && window.Android.saveProject) {
               window.Android.saveProject();
           }
       }
       
       openProject() {
           if (window.Android && window.Android.openProject) {
               window.Android.openProject();
           }
       }
       
       toggleFullScreen() {
           if (window.Android && window.Android.toggleFullScreen) {
               window.Android.toggleFullScreen();
           }
       }
   }
   
   // Initialize adaptive UI when DOM is ready
   document.addEventListener('DOMContentLoaded', () => {
       window.adaptiveUI = new AdaptiveUIController();
   });
   ```

### Step 2: Enhanced Android-JavaScript Bridge

Update `android-bridge.js` with connected display support:
```javascript
// Enhanced Android WebView JavaScript Interface for Connected Displays
window.AndroidMidi = {
    displayInfo: {
        isExternalDisplay: false,
        displayDensity: 1.0,
        windowSizeClass: 'compact'
    },
    
    // Display and layout management
    onDisplayChanged: null,
    onLayoutChanged: null,
    
    // MIDI functionality (existing)
    requestMIDIAccess: function() {
        return new Promise((resolve, reject) => {
            if (window.Android && window.Android.requestMidiAccess) {
                window.Android.requestMidiAccess();
                resolve({ inputs: [], outputs: [] });
            } else {
                reject(new Error('MIDI not supported'));
            }
        });
    },
    
    sendSysEx: function(data) {
        if (window.Android && window.Android.sendSysEx) {
            window.Android.sendSysEx(JSON.stringify(Array.from(data)));
        }
    },
    
    // Connected display features
    updateDisplayInfo: function(displayInfo) {
        this.displayInfo = displayInfo;
        if (this.onDisplayChanged) {
            this.onDisplayChanged(displayInfo);
        }
    },
    
    createNewInstance: function() {
        if (window.Android && window.Android.createNewInstance) {
            window.Android.createNewInstance();
        }
    },
    
    saveProject: function() {
        if (window.Android && window.Android.saveProject) {
            return window.Android.saveProject();
        }
    },
    
    openProject: function() {
        if (window.Android && window.Android.openProject) {
            window.Android.openProject();
        }
    },
    
    toggleFullScreen: function() {
        if (window.Android && window.Android.toggleFullScreen) {
            window.Android.toggleFullScreen();
        }
    },
    
    handleSampleDrop: function(sampleData) {
        if (window.Android && window.Android.handleSampleDrop) {
            window.Android.handleSampleDrop(sampleData);
        }
    },
    
    // Keyboard shortcut handling
    registerKeyboardShortcut: function(key, callback) {
        document.addEventListener('keydown', function(e) {
            if ((e.ctrlKey || e.metaKey) && e.key === key) {
                e.preventDefault();
                callback();
            }
        });
    }
};

// Override Web MIDI API
if (!navigator.requestMIDIAccess) {
    navigator.requestMIDIAccess = window.AndroidMidi.requestMIDIAccess;
}

// Setup global error handling
window.addEventListener('error', function(e) {
    if (window.Android && window.Android.reportError) {
        window.Android.reportError(e.message, e.filename, e.lineno);
    }
});
```

### Step 3: Update MainActivity with Connected Display Integration

Update `MainActivity.java`:
```java
public class MainActivity extends AppCompatActivity implements 
    ConnectedDisplayManager.ConnectedDisplayCallback,
    DesktopWindowingManager.DesktopWindowingCallback {
    
    private WebView webView;
    private MidiManager midiManager;
    private ConnectedDisplayManager displayManager;
    private DesktopWindowingManager windowingManager;
    private MultiInstanceManager instanceManager;
    private AdaptiveLayoutManager layoutManager;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        setupWebView();
        setupMidiManager();
        setupConnectedDisplays();
        setupDesktopWindowing();
        setupMultiInstance();
        
        // Handle instance-specific setup
        handleInstanceLaunch(getIntent());
    }
    
    private void setupConnectedDisplays() {
        displayManager = new ConnectedDisplayManager(this, this);
        layoutManager = new AdaptiveLayoutManager(this, webView);
    }
    
    private void setupDesktopWindowing() {
        windowingManager = new DesktopWindowingManager(this, this);
        
        // Enable custom header bar for desktop windowing
        if (displayManager.isDesktopModeAvailable()) {
            windowingManager.enableCustomHeaderBar();
        }
    }
    
    private void setupMultiInstance() {
        instanceManager = new MultiInstanceManager(this);
        instanceManager.enableMultiInstanceSupport();
    }
    
    private void handleInstanceLaunch(Intent intent) {
        int instanceId = intent.getIntExtra(MultiInstanceManager.EXTRA_INSTANCE_ID, 1);
        int displayId = intent.getIntExtra(MultiInstanceManager.EXTRA_LAUNCH_DISPLAY_ID, -1);
        
        Log.d("MainActivity", "Launching instance " + instanceId + " on display " + displayId);
        
        // Configure for specific display if specified
        if (displayId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Display targetDisplay = displayManager.getAllDisplays()[0]; // Find by ID
            layoutManager.updateLayoutForDisplay(targetDisplay);
        }
    }
    
    private void setupWebView() {
        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        
        // Enable zoom controls for desktop mode
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        
        // Add enhanced JavaScript interface
        webView.addJavascriptInterface(new EnhancedAndroidBridge(), "Android");
        
        // Load initial layout (mobile by default)
        webView.loadUrl("file:///android_asset/web/mobile-layout.html");
        
        // Setup WebView debugging
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }
    
    // Connected Display Callbacks
    @Override
    public void onExternalDisplayConnected(Display display) {
        Log.d("MainActivity", "External display connected: " + display.getDisplayId());
        layoutManager.updateLayoutForDisplay(display);
        updateDisplayInfoInWebView(display, true);
    }
    
    @Override
    public void onExternalDisplayDisconnected() {
        Log.d("MainActivity", "External display disconnected");
        Display primaryDisplay = getWindowManager().getDefaultDisplay();
        layoutManager.updateLayoutForDisplay(primaryDisplay);
        updateDisplayInfoInWebView(primaryDisplay, false);
    }
    
    @Override
    public void onDisplayChanged(Display display) {
        layoutManager.updateLayoutForDisplay(display);
        updateDisplayInfoInWebView(display, display.getDisplayId() != Display.DEFAULT_DISPLAY);
    }
    
    @Override
    public void onDesktopModeEnabled() {
        Log.d("MainActivity", "Desktop mode enabled");
        windowingManager.enableCustomHeaderBar();
        layoutManager.enableKeyboardShortcuts();
    }
    
    @Override
    public void onDesktopModeDisabled() {
        Log.d("MainActivity", "Desktop mode disabled");
    }
    
    // Desktop Windowing Callbacks
    @Override
    public void onWindowResized(int width, int height) {
        // Update WebView with new window dimensions
        String js = String.format(
            "if (window.adaptiveUI) { window.adaptiveUI.handleResize(%d, %d); }",
            width, height
        );
        webView.evaluateJavascript(js, null);
    }
    
    @Override
    public void onWindowModeChanged(boolean isDesktopMode) {
        String mode = isDesktopMode ? "desktop" : "mobile";
        String js = String.format(
            "if (window.AndroidMidi && window.AndroidMidi.onLayoutChanged) { " +
            "window.AndroidMidi.onLayoutChanged('%s'); }",
            mode
        );
        webView.evaluateJavascript(js, null);
    }
    
    @Override
    public void onFullScreenRequested() {
        windowingManager.requestFullScreen();
    }
    
    private void updateDisplayInfoInWebView(Display display, boolean isExternalDisplay) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        
        String displayInfo = String.format(
            "{ \"isExternalDisplay\": %b, \"displayDensity\": %f, \"widthPixels\": %d, \"heightPixels\": %d }",
            isExternalDisplay, metrics.density, metrics.widthPixels, metrics.heightPixels
        );
        
        String js = String.format(
            "if (window.AndroidMidi) { window.AndroidMidi.updateDisplayInfo(%s); }",
            displayInfo
        );
        
        webView.evaluateJavascript(js, null);
    }
    
    public class EnhancedAndroidBridge extends AndroidMidiBridge {
        
        @JavascriptInterface
        public void createNewInstance() {
            runOnUiThread(() -> {
                instanceManager.launchNewInstance();
            });
        }
        
        @JavascriptInterface
        public void createNewInstanceOnDisplay(int displayId) {
            runOnUiThread(() -> {
                Display[] displays = displayManager.getAllDisplays();
                for (Display display : displays) {
                    if (display.getDisplayId() == displayId) {
                        instanceManager.launchNewInstanceOnDisplay(display);
                        break;
                    }
                }
            });
        }
        
        @JavascriptInterface
        public void toggleFullScreen() {
            runOnUiThread(() -> {
                windowingManager.requestFullScreen();
            });
        }
        
        @JavascriptInterface
        public void saveProject() {
            // Implement project saving logic
            Log.d("EnhancedAndroidBridge", "Saving project...");
        }
        
        @JavascriptInterface
        public void openProject() {
            // Implement project opening logic
            Log.d("EnhancedAndroidBridge", "Opening project...");
        }
        
        @JavascriptInterface
        public void handleSampleDrop(String sampleData) {
            Log.d("EnhancedAndroidBridge", "Handling sample drop: " + sampleData);
            // Process dropped sample data
        }
        
        @JavascriptInterface
        public void reportError(String message, String filename, int lineNumber) {
            Log.e("WebViewError", String.format("JS Error: %s at %s:%d", message, filename, lineNumber));
        }
        
        @JavascriptInterface
        public void onLayoutChanged(String layoutType) {
            Log.d("EnhancedAndroidBridge", "Layout changed to: " + layoutType);
            // Handle layout change notifications from WebView
        }
    }
}
```

### Step 4: Create Adaptive CSS Styles

Create `mobile.css`:
```css
/* Mobile-first responsive design */
.mobile-layout {
    --header-height: 56px;
    --panel-gap: 8px;
    --touch-target-size: 48px;
}

#mobile-header {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    height: var(--header-height);
    background: var(--ColorMain, #b18fb1);
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 16px;
    z-index: 1000;
    box-shadow: 0 2px 4px rgba(0,0,0,0.1);
}

.mobile-container {
    padding-top: var(--header-height);
    padding: var(--panel-gap);
    height: 100vh;
    overflow-y: auto;
}

/* Touch-friendly controls */
.mobile-layout button {
    min-height: var(--touch-target-size);
    min-width: var(--touch-target-size);
    font-size: 16px;
    padding: 12px 16px;
}

/* Stack panels vertically on mobile */
.mobile-layout .panel {
    width: 100%;
    margin-bottom: var(--panel-gap);
}

/* Hide desktop-only features */
.mobile-layout .desktop-only {
    display: none;
}

/* Responsive grid for sample browser */
.mobile-layout .sample-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
    gap: var(--panel-gap);
}
```

Create `desktop.css`:
```css
/* Desktop layout optimized for connected displays */
.desktop-layout {
    --header-height: 48px;
    --panel-gap: 12px;
    --sidebar-width: 300px;
    --toolbar-height: 40px;
}

/* Custom caption bar for desktop windowing */
.custom-caption-bar {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    height: var(--header-height);
    background: var(--ColorMain, #b18fb1);
    border-bottom: 1px solid var(--ColorSecondary, #a282a8);
    z-index: 1000;
    
    /* Enable custom header bar */
    padding-top: env(titlebar-area-height, 0px);
    -webkit-app-region: drag;
}

.header-content {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 100%;
    padding: 0 16px;
    -webkit-app-region: no-drag;
}

.instance-controls {
    display: flex;
    gap: 8px;
    -webkit-app-region: no-drag;
}

.instance-controls button {
    padding: 6px 12px;
    background: var(--ColorTertiary, #96abde);
    border: none;
    border-radius: 4px;
    color: white;
    cursor: pointer;
    font-size: 14px;
}

.instance-controls button:hover {
    background: var(--ColorSecondary, #a282a8);
}

/* Main layout container */
.desktop-container {
    display: grid;
    grid-template-columns: var(--sidebar-width) 1fr;
    grid-template-rows: var(--header-height) 1fr;
    height: 100vh;
    gap: var(--panel-gap);
    padding: var(--header-height) var(--panel-gap) var(--panel-gap) var(--panel-gap);
}

/* Resizable panels */
.resizable-panel {
    background: var(--ColorSlate, #dbdddb);
    border-radius: 8px;
    border: 1px solid var(--ColorLightCharcoal, #b0babe);
    position: relative;
    overflow: hidden;
    resize: horizontal;
    min-width: 200px;
}

.resizable-panel:hover {
    border-color: var(--ColorMain, #b18fb1);
}

/* Desktop-specific features */
.desktop-layout .toolbar {
    height: var(--toolbar-height);
    background: var(--ColorCharcoal, #232424);
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 12px;
    border-bottom: 1px solid var(--ColorLightCharcoal, #b0babe);
}

.desktop-layout .toolbar button {
    padding: 4px 8px;
    background: transparent;
    border: 1px solid var(--ColorLightCharcoal, #b0babe);
    color: white;
    border-radius: 3px;
    cursor: pointer;
    font-size: 12px;
}

.desktop-layout .toolbar button:hover {
    background: var(--ColorMain, #b18fb1);
}

/* Desktop sample grid - more columns */
.desktop-layout .sample-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
    gap: 8px;
    padding: 12px;
    height: calc(100% - var(--toolbar-height));
    overflow-y: auto;
}

/* Context menus for desktop */
.context-menu {
    position: absolute;
    background: var(--ColorSlate, #dbdddb);
    border: 1px solid var(--ColorLightCharcoal, #b0babe);
    border-radius: 4px;
    padding: 4px 0;
    box-shadow: 0 4px 12px rgba(0,0,0,0.2);
    z-index: 2000;
    min-width: 150px;
}

.context-menu-item {
    padding: 8px 12px;
    cursor: pointer;
    font-size: 14px;
    border: none;
    background: none;
    width: 100%;
    text-align: left;
}

.context-menu-item:hover {
    background: var(--ColorMain, #b18fb1);
    color: white;
}

/* Window controls for multi-instance */
.window-controls {
    position: absolute;
    top: 8px;
    right: 8px;
    display: flex;
    gap: 4px;
    z-index: 1001;
}

.window-control-btn {
    width: 24px;
    height: 24px;
    border: none;
    border-radius: 50%;
    cursor: pointer;
    font-size: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
}

.minimize-btn {
    background: #ffd700;
    color: #333;
}

.maximize-btn {
    background: #00ff00;
    color: #333;
}

.close-btn {
    background: #ff4444;
    color: white;
}

/* Drag and drop indicators */
.drop-zone {
    border: 2px dashed var(--ColorMain, #b18fb1);
    border-radius: 8px;
    padding: 20px;
    text-align: center;
    color: var(--ColorMain, #b18fb1);
    transition: all 0.3s ease;
}

.drop-zone.active {
    background: rgba(177, 143, 177, 0.1);
    border-color: var(--ColorSound, #FF00E0);
    color: var(--ColorSound, #FF00E0);
}

/* Hide mobile-only features */
.desktop-layout .mobile-only {
    display: none;
}

/* Responsive breakpoints for desktop */
@media (min-width: 1200px) {
    .desktop-container {
        grid-template-columns: var(--sidebar-width) 1fr 300px;
    }
}

@media (min-width: 1600px) {
    .desktop-layout .sample-grid {
        grid-template-columns: repeat(auto-fill, minmax(80px, 1fr));
    }
}

/* Animation for layout transitions */
.layout-transition {
    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

/* Accessibility improvements for desktop */
.desktop-layout *:focus {
    outline: 2px solid var(--ColorSound, #FF00E0);
    outline-offset: 2px;
}

/* Custom scrollbars for desktop */
.desktop-layout ::-webkit-scrollbar {
    width: 12px;
}

.desktop-layout ::-webkit-scrollbar-track {
    background: var(--ColorLightGray, #dcdcdc);
}

.desktop-layout ::-webkit-scrollbar-thumb {
    background: var(--ColorMain, #b18fb1);
    border-radius: 6px;
}

.desktop-layout ::-webkit-scrollbar-thumb:hover {
    background: var(--ColorSecondary, #a282a8);
}
```

Create `adaptive-responsive.css`:
```css
/* Adaptive CSS variables that change based on display */
:root {
    /* Default mobile values */
    --adaptive-font-size: 16px;
    --adaptive-spacing: 16px;
    --adaptive-border-radius: 8px;
    --adaptive-shadow: 0 2px 4px rgba(0,0,0,0.1);
}

/* Tablet adjustments */
@media (min-width: 600px) and (max-width: 839px) {
    :root {
        --adaptive-font-size: 15px;
        --adaptive-spacing: 20px;
        --adaptive-border-radius: 6px;
    }
}

/* Desktop/Large screen adjustments */
@media (min-width: 840px) {
    :root {
        --adaptive-font-size: 14px;
        --adaptive-spacing: 24px;
        --adaptive-border-radius: 4px;
        --adaptive-shadow: 0 4px 8px rgba(0,0,0,0.15);
    }
}

/* High density display adjustments */
@media (-webkit-min-device-pixel-ratio: 2) {
    :root {
        --adaptive-border-width: 0.5px;
    }
}

/* CSS custom properties controlled by JavaScript */
.adaptive-container {
    font-size: var(--adaptive-font-size);
    padding: var(--adaptive-spacing);
    border-radius: var(--adaptive-border-radius);
    box-shadow: var(--adaptive-shadow);
}

/* Window size class utilities */
.window-compact {
    /* Styles for phones */
}

.window-medium {
    /* Styles for tablets and foldables */
}

.window-expanded {
    /* Styles for large tablets and desktop displays */
}

/* Connected display specific styles */
.external-display {
    /* Enhanced contrast for external monitors */
    filter: contrast(1.1) brightness(1.05);
}

.primary-display {
    /* Optimized for mobile OLED screens */
    filter: contrast(1.0) brightness(1.0);
}

/* Multi-instance window differentiation */
.instance-1 { border-top: 3px solid var(--Color1, #82c9ec); }
.instance-2 { border-top: 3px solid var(--Color2, #82ec88); }
.instance-3 { border-top: 3px solid var(--Color3, #faff4a); }
.instance-4 { border-top: 3px solid var(--Color4, #47f3e3); }
.instance-5 { border-top: 3px solid var(--Color5, #f45050); }
.instance-6 { border-top: 3px solid var(--Color6, #a475f9); }
```

### WebAssembly in Android

Android WebView supports WebAssembly, so the original audio processing modules can be reused.

### Step 1: Prepare WASM Files

1. **Copy WASM modules**:
   ```bash
   cp data/*.wasm app/src/main/assets/web/wasm/
   ```

2. **Verify WASM loading in WebView**:
   Test that libsndfile.wasm, libsamplerate.wasm, and other modules load correctly.

### Step 2: Alternative Native Audio Processing

If WASM performance is insufficient, implement native audio processing:

1. **Add NDK support** to `build.gradle`:
   ```gradle
   android {
       externalNativeBuild {
           cmake {
               path "src/main/cpp/CMakeLists.txt"
           }
       }
       ndkVersion "25.1.8937393"
   }
   ```

2. **Create native audio processing**:
   - Port libsndfile functionality to native C++
   - Use Android NDK for audio sample rate conversion
   - Implement JNI bridges for JavaScript access

## Phase 6: File Management and Storage

### Step 1: Implement File Operations

Create `FileManager.java`:
```java
public class FileManager {
    private static final String BACKUP_DIR = "EP133_Backups";
    private static final String SAMPLES_DIR = "EP133_Samples";
    
    public static File getBackupDirectory(Context context) {
        File dir = new File(context.getExternalFilesDir(null), BACKUP_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
    
    public static boolean saveBackup(Context context, String filename, byte[] data) {
        try {
            File backupFile = new File(getBackupDirectory(context), filename);
            FileOutputStream fos = new FileOutputStream(backupFile);
            fos.write(data);
            fos.close();
            return true;
        } catch (IOException e) {
            Log.e("FileManager", "Error saving backup", e);
            return false;
        }
    }
    
    // Additional file operations...
}
```

### Step 2: Handle Scoped Storage (Android 10+)

Implement proper file access for Android 10+ scoped storage:
```java
private void requestStoragePermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        }
    }
}
```

## Phase 7: Android 16 Connected Displays Testing and Development

### Connected Displays Testing Strategy

Android 16's connected displays feature requires specific testing approaches to ensure your EP-133 Sample Tool works seamlessly across different display configurations.

### Step 1: Set Up Connected Display Testing Environment

1. **Hardware Requirements**:
   ```bash
   # Supported devices for Android 16 QPR1 Beta 2
   # - Pixel 8 series
   # - Pixel 9 series
   # - USB-C to DisplayPort adapter or USB-C dock
   # - External monitor with DisplayPort input
   # - USB-C hub with multiple ports for EP-133 connection
   ```

2. **Enable Developer Features**:
   ```bash
   # On Android 16 device:
   # Settings > Developer Options > Desktop Experience Features
   # Enable "Connected Display Support"
   # Enable "Desktop Windowing"
   # Enable "Force Desktop Mode" (for testing)
   ```

3. **Testing Configuration**:
   ```bash
   # Connect via USB-C dock:
   # Phone -> USB-C Hub -> External Monitor (DisplayPort)
   #                   -> EP-133 (USB)
   #                   -> Keyboard/Mouse (USB)
   #                   -> Audio Interface (USB, optional)
   ```

### Step 2: Connected Display Test Cases

Create comprehensive test cases for different scenarios:

```java
public class ConnectedDisplayTestSuite {
    
    @Test
    public void testDisplayDetection() {
        // Verify app detects external display connection
        // Verify display metrics are correctly obtained
        // Verify display density changes are handled
    }
    
    @Test 
    public void testLayoutSwitching() {
        // Test mobile to desktop layout transition
        // Verify UI elements reposition correctly
        // Test layout persistence across orientation changes
    }
    
    @Test
    public void testMultiInstanceLaunch() {
        // Launch multiple instances on different displays
        // Verify instances maintain separate states
        // Test drag and drop between instances
    }
    
    @Test
    public void testDesktopWindowingFeatures() {
        // Test window resizing
        // Test window tiling and snapping
        // Test custom caption bar functionality
        // Test keyboard shortcuts
    }
    
    @Test
    public void testMidiWithConnectedDisplay() {
        // Test EP-133 MIDI communication on external display
        // Verify USB hub compatibility
        // Test sample upload/download performance
    }
    
    @Test
    public void testPeripheralSupport() {
        // Test external keyboard input
        // Test mouse interactions and hover states
        // Test right-click context menus
        // Test audio routing to external speakers
    }
}
```

### Step 3: Performance Testing for Connected Displays

Create performance monitoring for desktop mode:

```java
public class ConnectedDisplayPerformanceMonitor {
    private static final String TAG = "DisplayPerformance";
    private long frameStartTime;
    private int frameCount = 0;
    private float averageFPS = 0;
    
    public void startFrameMonitoring() {
        frameStartTime = System.currentTimeMillis();
    }
    
    public void onFrameRendered() {
        frameCount++;
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - frameStartTime >= 1000) {
            averageFPS = frameCount * 1000f / (currentTime - frameStartTime);
            Log.d(TAG, "Average FPS: " + averageFPS);
            
            // Alert if performance drops below threshold
            if (averageFPS < 30) {
                Log.w(TAG, "Performance warning: FPS below 30");
            }
            
            frameCount = 0;
            frameStartTime = currentTime;
        }
    }
    
    public void monitorMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        
        float memoryUsagePercent = (usedMemory * 100f) / maxMemory;
        
        Log.d(TAG, String.format("Memory usage: %.1f%% (%d MB / %d MB)", 
            memoryUsagePercent, usedMemory / 1024 / 1024, maxMemory / 1024 / 1024));
        
        if (memoryUsagePercent > 80) {
            Log.w(TAG, "High memory usage detected");
        }
    }
    
    public void monitorDisplayMetrics(Display display) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        
        Log.d(TAG, String.format("Display %d: %dx%d @ %.1f dpi (density: %.2f)", 
            display.getDisplayId(), metrics.widthPixels, metrics.heightPixels, 
            metrics.densityDpi, metrics.density));
    }
}
```

### Step 4: Debug Connected Display Issues

Common issues and debugging approaches:

1. **Display Detection Problems**:
   ```bash
   # Check connected displays
   adb shell dumpsys display
   
   # Monitor display changes
   adb logcat | grep "DisplayManager\|WindowManager"
   ```

2. **WebView Performance on External Displays**:
   ```javascript
   // Debug WebView performance
   console.time('layoutSwitch');
   window.adaptiveUI.switchToDesktopLayout();
   console.timeEnd('layoutSwitch');
   
   // Monitor memory usage
   window.addEventListener('resize', () => {
       console.log('Window size:', window.innerWidth, 'x', window.innerHeight);
       console.log('Memory usage:', performance.memory);
   });
   ```

3. **MIDI Communication Issues**:
   ```bash
   # Check USB devices
   adb shell cat /proc/bus/usb/devices
   
   # Monitor USB events
   adb logcat | grep "USB\|MIDI"
   ```

### Step 5: Automated Testing for Multiple Display Configurations

Create automated tests for different display scenarios:

```java
@RunWith(AndroidJUnit4.class)
public class MultiDisplayAutomatedTest {
    
    @Rule
    public ActivityTestRule<MainActivity> activityRule = 
        new ActivityTestRule<>(MainActivity.class);
    
    @Test
    public void testSingleDisplayMode() {
        // Test app behavior on phone display only
        onView(withId(R.id.webview))
            .check(matches(isDisplayed()));
        
        // Verify mobile layout is active
        onWebView()
            .withElement(findElement(Locator.CLASS_NAME, "mobile-layout"))
            .check(webMatches(getCurrentUrl(), containsString("mobile")));
    }
    
    @Test
    public void testDualDisplayMode() {
        // Simulate external display connection
        Intent intent = new Intent();
        intent.putExtra("simulate_external_display", true);
        
        activityRule.launchActivity(intent);
        
        // Verify desktop layout is active
        onWebView()
            .withElement(findElement(Locator.CLASS_NAME, "desktop-layout"))
            .check(webMatches(getCurrentUrl(), containsString("desktop")));
    }
    
    @Test
    public void testInstanceLaunchOnExternalDisplay() {
        // Test launching new instance on external display
        onView(withId(R.id.webview))
            .perform(webClick(findElement(Locator.ID, "new-instance-btn")));
        
        // Verify new instance appears
        // This requires custom testing framework for multi-instance testing
    }
    
    @Test
    public void testDragDropBetweenInstances() {
        // Test drag and drop functionality between instances
        // Requires specialized testing tools for cross-instance interaction
    }
}
```

### Step 6: Real-World Testing Scenarios

Test the EP-133 Sample Tool in realistic music production scenarios:

1. **Studio Setup Testing**:
   - Phone connected to studio monitor
   - EP-133 connected via USB hub
   - External MIDI keyboard connected
   - Audio interface for monitoring
   - Test full workflow: sample creation, editing, backup

2. **Mobile Producer Testing**:
   - Portable monitor with phone
   - EP-133 in battery mode
   - Bluetooth keyboard/mouse
   - Test quick sample editing and project management

3. **Collaborative Testing**:
   - Multiple instances running on different displays
   - Share samples between instances
   - Test project synchronization

4. **Performance Testing**:
   - Long production sessions (2+ hours)
   - Large sample libraries (100+ samples)
   - Multiple simultaneous MIDI operations
   - Memory leak detection

### Step 7: Emulator Testing (When Available)

Once Android Emulator supports connected displays:

```bash
# Future emulator commands (when supported)
emulator -avd Android16_API35 -feature ConnectedDisplays
emulator -avd Android16_API35 -multi-display
```

### Step 8: Beta Feedback and Iteration

Participate in Android 16 beta feedback:

1. **Report Issues**:
   ```bash
   # Use official feedback channels
   # Document specific connected display issues
   # Provide logcat outputs and screen recordings
   ```

2. **Performance Metrics**:
   ```bash
   # Collect and report performance data
   # Compare single vs dual display performance
   # Monitor battery usage with external displays
   ```

3. **Feature Requests**:
   ```bash
   # Request additional connected display APIs
   # Suggest improvements for audio app workflows
   # Propose better multi-instance management
   ```

### Step 1: Set Up Development Testing

1. **Enable USB Debugging** on Android device
2. **Configure EP-133 connection**:
   - Use USB OTG adapter if needed
   - Test USB Host capability on device
3. **Set up logging**:
   ```bash
   adb logcat | grep EP133
   ```

### Step 2: Testing Protocol

1. **Unit Tests**:
   - MIDI message parsing
   - SysEx protocol implementation
   - File operations

2. **Integration Tests**:
   - EP-133 device detection
   - Sample upload/download
   - Backup/restore operations

3. **UI Tests**:
   - WebView functionality
   - JavaScript bridge communication
   - Responsive design on different screen sizes

### Step 3: Debug Common Issues

1. **USB Permission Issues**:
   ```java
   private void requestUsbPermission(UsbDevice device) {
       PendingIntent permissionIntent = PendingIntent.getBroadcast(
           this, 0, new Intent(ACTION_USB_PERMISSION), 0);
       usbManager.requestPermission(device, permissionIntent);
   }
   ```

2. **WebView Console Logging**:
   ```java
   webView.setWebChromeClient(new WebChromeClient() {
       @Override
       public boolean onConsoleMessage(ConsoleMessage cm) {
           Log.d("WebView", cm.message());
           return true;
       }
   });
   ```

## Phase 8: Advanced Features Implementation

### Step 1: Custom Color Schemes

Implement the custom theming system:
```java
public class ThemeManager {
    public static void applyCustomColors(WebView webView, String colorScheme) {
        String js = String.format(
            "if (window.updateCustomColors) { window.updateCustomColors('%s'); }",
            colorScheme
        );
        webView.evaluateJavascript(js, null);
    }
}
```

### Step 2: Offline Functionality

Ensure all features work without internet:
- Bundle all required assets
- Implement local file caching
- Handle offline error states

### Step 3: Android-Specific UI Enhancements

1. **Responsive Design**:
   - Adapt UI for mobile screen sizes
   - Implement touch-friendly controls
   - Add Android-style navigation

2. **Performance Optimization**:
   - Optimize WebView performance
   - Implement efficient MIDI buffering
   - Add progress indicators for long operations

## Phase 9: Build and Distribution

### Step 1: Configure Build Variants

Set up debug and release builds in `build.gradle`:
```gradle
android {
    buildTypes {
        debug {
            debuggable true
            minifyEnabled false
            applicationIdSuffix ".debug"
        }
        release {
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

### Step 2: App Signing and Distribution

1. **Generate signing key**:
   ```bash
   keytool -genkey -v -keystore ep133-release-key.keystore -alias ep133-key -keyalg RSA -keysize 2048 -validity 10000
   ```

2. **Configure signing** in `build.gradle`:
   ```gradle
   android {
       signingConfigs {
           release {
               storeFile file('ep133-release-key.keystore')
               storePassword 'your-password'
               keyAlias 'ep133-key'
               keyPassword 'your-password'
           }
       }
   }
   ```

### Step 3: Generate APK

```bash
./gradlew assembleRelease
```

## Phase 10: Documentation and Maintenance

### Step 1: Create User Documentation

Document Android-specific features:
- USB connection setup
- Troubleshooting guide
- Performance tips
- Compatibility information

### Step 2: Version Control Strategy

Maintain separate branches:
- `main` - Original Electron app
- `android-clone` - Android implementation
- `android-release` - Release-ready Android builds

### Step 3: Continuous Integration

Set up automated builds:
```yaml
# .github/workflows/android.yml
name: Android CI
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v2
    - name: Set up JDK 11
      uses: actions/setup-java@v2
      with:
        java-version: '11'
        distribution: 'adopt'
    - name: Build with Gradle
      run: ./gradlew assembleDebug
```

## Troubleshooting Common Issues

### MIDI Communication Problems
- Check USB OTG support on device
- Verify EP-133 firmware compatibility
- Test with different USB cables/adapters
- Monitor USB permissions and device detection

### WebView Performance Issues
- Enable hardware acceleration
- Optimize JavaScript execution
- Consider native implementation for critical paths
- Profile memory usage and optimize

### Audio Processing Issues
- Test WASM module loading
- Verify audio format support
- Check sample rate conversion accuracy
- Monitor audio latency and buffer sizes

## Additional Resources

### Documentation Links
- [Android USB Host API](https://developer.android.com/guide/topics/connectivity/usb/host)
- [WebView Best Practices](https://developer.android.com/guide/webapps/webview)
- [EP-133 MIDI Implementation](https://github.com/garrettjwilke/ep_133_sysex_thingy)

### Development Tools
- Android Studio with SDK tools
- Chrome DevTools for WebView debugging
- USB protocol analyzers for MIDI debugging
- Audio analysis tools for sample verification

### Community Resources
- Teenage Engineering developer forums
- Android MIDI development communities
- EP-133 user communities for testing feedback

---

## Project Timeline Estimate (Updated for Android 16 Features)

- **Phase 1-2**: 2-3 weeks (Setup and project creation with Android 16 support)
- **Phase 3**: 3-4 weeks (Android 16 connected displays implementation)
- **Phase 4**: 2-3 weeks (Enhanced MIDI implementation)
- **Phase 5**: 3-4 weeks (Adaptive UI with desktop/mobile layouts)
- **Phase 6**: 1-2 weeks (Audio processing integration)
- **Phase 7**: 1-2 weeks (File management)
- **Phase 8**: 3-4 weeks (Connected displays testing and debugging)
- **Phase 9**: 2-3 weeks (Advanced features and multi-instance support)
- **Phase 10**: 1-2 weeks (Build and distribution)
- **Phase 11**: 1-2 weeks (Documentation and beta feedback)

**Total Estimated Time**: 16-24 weeks for full implementation with Android 16 connected displays support

### Key Advantages of Android 16 Implementation

1. **Professional Workflow**: Transform any Android phone into a desktop-class EP-133 management station
2. **Multi-Instance Productivity**: Run multiple instances for complex projects and comparisons
3. **Studio Integration**: Seamless integration with existing studio setups via external monitors
4. **Future-Proof**: Built on the latest Android platform with cutting-edge display technology
5. **Hardware Flexibility**: Works with various USB-C docks and display configurations
6. **Enhanced User Experience**: Adaptive UI that automatically optimizes for any display size

This comprehensive guide provides the foundation for creating the most advanced EP-133 Sample Tool ever built, leveraging Android 16's revolutionary connected displays support to deliver a truly professional music production experience on mobile devices.
