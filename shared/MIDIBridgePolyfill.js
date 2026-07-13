// =============================================================================
// Multi-platform MIDI bridge polyfill
//
// Overrides navigator.requestMIDIAccess() and routes MIDI I/O through the
// host platform's native MIDI APIs.
//
// Supported platforms:
//   JUCE     – window.__JUCE__.backend emitEvent('__juce__invoke') / addEventListener('midiIn')
//   Android  – window.EP133Bridge.getMidiDevices() / .sendMidi()
//   iOS      – window.webkit.messageHandlers.midibridge.postMessage()
//   Browser  – falls through to native Web MIDI API (Electron / Chrome)
// =============================================================================
(function () {
  'use strict';

  // midiListeners[portId] = [handler, ...]
  var midiListeners = {};

  // iOS async callback resolution
  var pendingCallbacks = {};
  var callbackCounter = 0;

  // Stored reference to last MIDIAccess for statechange notifications
  var lastMIDIAccess = null;
  var lastOptions = null;
  var stateChangeListeners = [];

  // ---- Platform detection ----

  function detectPlatform() {
    if (typeof window.__JUCE__ !== 'undefined') return 'juce';
    if (typeof window.EP133Bridge !== 'undefined') return 'android';
    if (window.webkit && window.webkit.messageHandlers &&
        window.webkit.messageHandlers.midibridge) return 'ios';
    return null;
  }

  // ---- JUCE 8 native interop ----
  // JUCE 8 has NO window.__JUCE__.invoke(). A native function registered with
  // WebBrowserComponent::Options::withNativeFunction is called by emitting the
  // "__juce__invoke" event on window.__JUCE__.backend with {name, params, resultId}
  // and awaiting a "__juce__complete" event carrying {promiseId=resultId, result}.
  // (JUCE's frontend lib provides getNativeFunction() for this; we replicate the
  // wire protocol here so the shared polyfill stays a dependency-free ES5 IIFE.)
  var juceInvokeFn = null;
  function juceInvoke(name, paramsArray) {
    if (!juceInvokeFn) {
      var backend = window.__JUCE__.backend;
      var pending = {};
      var nextId  = 0;
      backend.addEventListener('__juce__complete', function (payload) {
        var resolve = pending[payload.promiseId];
        if (resolve) { delete pending[payload.promiseId]; resolve(payload.result); }
      });
      juceInvokeFn = function (n, p) {
        return new Promise(function (resolve) {
          var id = nextId++;
          pending[id] = resolve;
          backend.emitEvent('__juce__invoke', { name: n, params: p, resultId: id });
        });
      };
    }
    return juceInvokeFn(name, paramsArray);
  }

  // ---- Native bridge abstraction ----

  function getNativeDevices() {
    var platform = detectPlatform();

    if (platform === 'juce') {
      return juceInvoke('getMidiDevices', []);
    }

    if (platform === 'android') {
      var json = window.EP133Bridge.getMidiDevices();
      return Promise.resolve(JSON.parse(json));
    }

    if (platform === 'ios') {
      return new Promise(function (resolve) {
        var cbId = '_cb_' + (++callbackCounter);
        pendingCallbacks[cbId] = resolve;
        window.webkit.messageHandlers.midibridge.postMessage({
          action: 'getMidiDevices',
          callbackId: cbId
        });
      });
    }

    return null;
  }

  function sendNativeMidi(portId, data) {
    var platform = detectPlatform();

    if (platform === 'juce') {
      juceInvoke('sendMidi', [portId, Array.from(data)]);
      return;
    }

    if (platform === 'android') {
      window.EP133Bridge.sendMidi(portId, JSON.stringify(Array.from(data)));
      return;
    }

    if (platform === 'ios') {
      window.webkit.messageHandlers.midibridge.postMessage({
        action: 'sendMidi',
        portId: portId,
        data: Array.from(data)
      });
      return;
    }
  }

  // ---- iOS callback resolution (called from Swift) ----

  window.__ep133_resolveCallback = function (callbackId, resultJSON) {
    var cb = pendingCallbacks[callbackId];
    if (cb) {
      delete pendingCallbacks[callbackId];
      cb(typeof resultJSON === 'string' ? JSON.parse(resultJSON) : resultJSON);
    }
  };

  // ---- Incoming MIDI from native (called from Swift / Kotlin / JUCE) ----

  window.__ep133_onMidiIn = function (portId, dataArray) {
    var listeners = midiListeners[portId] || [];
    var msg = {
      data: new Uint8Array(dataArray),
      target: { id: portId }
    };
    for (var i = 0; i < listeners.length; ++i) {
      try { listeners[i](msg); } catch (e) { console.error(e); }
    }
  };

  // ---- Install the polyfill ----

  // Resolve once a native bridge is present. JUCE sets up window.__JUCE__
  // asynchronously, so a call made at app startup may arrive before it exists;
  // Android/iOS bridges are synchronous, so this resolves immediately for them.
  function whenBridgeReady() {
    return new Promise(function (resolve, reject) {
      if (detectPlatform()) { resolve(); return; }
      var attempts = 0;
      var timer = setInterval(function () {
        if (detectPlatform()) { clearInterval(timer); resolve(); }
        else if (++attempts >= 100) { clearInterval(timer); reject(new Error('no MIDI bridge')); }
      }, 100);
    });
  }

  // Wire INCOMING MIDI. JUCE pushes it via its own event system; Android/iOS
  // deliver it through their own global callbacks (nothing to wire here).
  function wireIncoming() {
    if (detectPlatform() === 'juce') {
      // Incoming MIDI arrives as a JUCE backend event (emitEventIfBrowserIsVisible
      // "midiIn" on the C++ side) — listen on window.__JUCE__.backend, not __JUCE__.
      window.__JUCE__.backend.addEventListener('midiIn', function (event) {
        window.__ep133_onMidiIn(event.portId, event.data);
      });
    }
  }

  function installOverride() {
    // Override the Web MIDI API. Safe to call before the native bridge is ready:
    // the query awaits whenBridgeReady() and detects the platform lazily, so the
    // app sees navigator.requestMIDIAccess immediately even while __JUCE__ inits.
    navigator.requestMIDIAccess = function (options) {
      return whenBridgeReady().then(getNativeDevices).then(function (devices) {
        var inputs  = new Map();
        var outputs = new Map();

        (devices.inputs || []).forEach(function (d) {
          midiListeners[d.id] = midiListeners[d.id] || [];
          var port = {
            id:           d.id,
            name:         d.name,
            manufacturer: '',
            state:        'connected',
            connection:   'open',
            type:         'input',
            addEventListener: function (type, fn) {
              if (type === 'midimessage') {
                midiListeners[d.id] = midiListeners[d.id] || [];
                midiListeners[d.id].push(fn);
              }
            },
            removeEventListener: function (type, fn) {
              if (type === 'midimessage') {
                midiListeners[d.id] = (midiListeners[d.id] || []).filter(
                  function (f) { return f !== fn; });
              }
            }
          };
          Object.defineProperty(port, 'onmidimessage', {
            get: function () {
              return (midiListeners[d.id] || [])[0] || null;
            },
            set: function (fn) {
              midiListeners[d.id] = fn ? [fn] : [];
            }
          });
          inputs.set(d.id, port);
        });

        (devices.outputs || []).forEach(function (d) {
          outputs.set(d.id, {
            id:           d.id,
            name:         d.name,
            manufacturer: '',
            state:        'connected',
            connection:   'open',
            type:         'output',
            send: function (data) {
              sendNativeMidi(d.id, data);
            },
            clear: function () {},
            addEventListener:    function () {},
            removeEventListener: function () {}
          });
        });

        var access = {
          inputs:       inputs,
          outputs:      outputs,
          sysexEnabled: !!(options && options.sysex),
          onstatechange: null,
          addEventListener: function (type, fn) {
            if (type === 'statechange') stateChangeListeners.push(fn);
          },
          removeEventListener: function (type, fn) {
            if (type === 'statechange') {
              stateChangeListeners = stateChangeListeners.filter(function (f) { return f !== fn; });
            }
          }
        };

        lastMIDIAccess = access;
        lastOptions = options;
        return access;
      });
    };
  }

  // ---- Device change notification (called from native code) ----

  window.__ep133_onDevicesChanged = function () {
    console.log('[EP133] Devices changed, re-querying...');
    // Re-call requestMIDIAccess to rebuild device maps, then fire statechange
    if (navigator.requestMIDIAccess) {
      navigator.requestMIDIAccess(lastOptions || {}).then(function (newAccess) {
        // Copy the statechange handler from the old access
        if (lastMIDIAccess && lastMIDIAccess.onstatechange) {
          newAccess.onstatechange = lastMIDIAccess.onstatechange;
        }
        lastMIDIAccess = newAccess;

        // Fire statechange on the new access object
        var evt = { port: null };
        if (newAccess.onstatechange) {
          try { newAccess.onstatechange(evt); } catch (e) { console.error(e); }
        }
        for (var i = 0; i < stateChangeListeners.length; i++) {
          try { stateChangeListeners[i](evt); } catch (e) { console.error(e); }
        }
      });
    }
  };

  // ---- Initialization ----

  // In a native host, install the Web MIDI override NOW so the app detects support
  // even before the bridge finishes initialising. window.__ep133_expectJuceBridge is
  // set synchronously by the JUCE wrapper (whose __JUCE__ object arrives async); the
  // Android/iOS bridges are already present so detectPlatform() covers them. In a real
  // browser (no bridge, no marker) leave the native Web MIDI API untouched.
  if (detectPlatform() || window.__ep133_expectJuceBridge) {
    installOverride();

    var attempts = 0;
    var timer = setInterval(function () {
      if (detectPlatform()) {
        clearInterval(timer);
        wireIncoming();
        console.log('[EP133] MIDI bridge ready (' + detectPlatform() + ')');
      } else if (++attempts >= 100) {
        clearInterval(timer);
        console.warn('[EP133] MIDI bridge never appeared after 10s');
      }
    }, 100);
  } else {
    console.log('[EP133] No native bridge detected, using native Web MIDI API');
  }
})();
