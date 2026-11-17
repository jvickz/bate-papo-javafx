package br.com.batepapo.client;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import javafx.stage.Stage;
import java.util.ArrayList;

final class WindowsTitleBar {
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_CAPTION_COLOR = 35;
    private static final int DWMWA_TEXT_COLOR = 36;
    private static final int SPI_GETHIGHCONTRAST = 0x0042;
    private static final int SPI_GETCLIENTAREAANIMATION = 0x1042;

    private WindowsTitleBar() {}

    /** Call immediately after showing the decorated Stage on its JavaFX thread. */
    static void apply(Stage stage) {
        if (!Platform.isWindows() || !stage.isShowing() || stage.getTitle().isEmpty()) return;
        try {
            Pointer window = windowWithTitle(stage.getTitle());
            if (window != null) applyColors(window);
        } catch (LinkageError unavailable) {
            // Windows can still draw all native controls when a system library is unavailable.
        }
    }

    private static Pointer windowWithTitle(String title) {
        var user = User.INSTANCE;
        var buffer = new char[title.length() + 2];
        var candidates = new ArrayList<Pointer>();
        user.EnumThreadWindows(Kernel.INSTANCE.GetCurrentThreadId(), (window, ignored) -> {
            int length = user.GetWindowTextW(window, buffer, buffer.length);
            if (length == title.length() && title.equals(new String(buffer, 0, length))) {
                candidates.add(window);
            }
            return true;
        }, null);
        if (candidates.size() == 1) return candidates.getFirst();
        var active = user.GetActiveWindow();
        return candidates.contains(active) ? active : null;
    }

    static void applyColors(Pointer window) {
        // HIGHCONTRASTW contains two DWORDs and one pointer.
        int size = 2 * Integer.BYTES + Native.POINTER_SIZE;
        try (var contrast = new Memory(size)) {
            contrast.clear();
            contrast.setInt(0, size);
            if (!User.INSTANCE.SystemParametersInfoW(SPI_GETHIGHCONTRAST, size, contrast, 0)
                    || (contrast.getInt(Integer.BYTES) & 1) != 0) return;
        }
        var dwm = Dwm.INSTANCE;
        dwm.DwmSetWindowAttribute(window, DWMWA_USE_IMMERSIVE_DARK_MODE, new IntByReference(1), Integer.BYTES);
        // Windows COLORREF is 0x00BBGGRR. Older DWM versions ignore these attributes.
        dwm.DwmSetWindowAttribute(window, DWMWA_CAPTION_COLOR, new IntByReference(0x201308), Integer.BYTES);
        dwm.DwmSetWindowAttribute(window, DWMWA_TEXT_COLOR, new IntByReference(0xEBE9D2), Integer.BYTES);
    }

    static boolean animationsEnabled() {
        if (!Platform.isWindows()) return true;
        try {
            var enabled = new IntByReference();
            return !User.INSTANCE.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, enabled.getPointer(), 0)
                    || enabled.getValue() != 0;
        } catch (LinkageError unavailable) {
            return true;
        }
    }

    private interface User extends StdCallLibrary {
        User INSTANCE = Native.load("user32", User.class);
        interface WindowCallback extends StdCallCallback {
            boolean invoke(Pointer window, Pointer data);
        }
        boolean EnumThreadWindows(int thread, WindowCallback callback, Pointer data);
        int GetWindowTextW(Pointer window, char[] buffer, int length);
        Pointer GetActiveWindow();
        boolean SystemParametersInfoW(int action, int size, Pointer value, int flags);
    }

    private interface Kernel extends StdCallLibrary {
        Kernel INSTANCE = Native.load("kernel32", Kernel.class);
        int GetCurrentThreadId();
    }

    private interface Dwm extends StdCallLibrary {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);
        int DwmSetWindowAttribute(Pointer window, int attribute, IntByReference value, int size);
    }
}
