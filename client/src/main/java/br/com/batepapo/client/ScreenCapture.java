package br.com.batepapo.client;

import dev.onvoid.webrtc.client.LocalVideo;
import dev.onvoid.webrtc.client.ScreenOptions;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;

import java.util.ArrayList;
import java.util.List;

interface ScreenCapture {
    List<VoiceEngine.Screen> displays(WebRtc runtime);
    LocalVideo open(WebRtc runtime, VoiceEngine.Screen screen);

    ScreenCapture DESKTOP = new ScreenCapture() {
        @Override public List<VoiceEngine.Screen> displays(WebRtc runtime) {
            var displays = new ArrayList<VoiceEngine.Screen>();
            for (var source : runtime.media().devices().screens()) {
                String name = "Monitor " + (displays.size() + 1);
                if (source.title != null && !source.title.isBlank()) name += " · " + source.title;
                displays.add(new VoiceEngine.Screen(source.id, name));
            }
            return List.copyOf(displays);
        }

        @Override public LocalVideo open(WebRtc runtime, VoiceEngine.Screen screen) {
            var source = new DesktopSource(screen.name(), screen.id());
            return runtime.media().screen(ScreenOptions.screen(source).maxSize(3840, 2160).frameRate(30).build());
        }
    };
}
