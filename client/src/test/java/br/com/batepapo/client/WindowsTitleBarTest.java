package br.com.batepapo.client;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.EventQueue;
import java.awt.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsTitleBarTest {
    private interface User extends StdCallLibrary {
        Pointer GetWindowDC(Pointer window);
        int ReleaseDC(Pointer window, Pointer dc);
        boolean PrintWindow(Pointer window, Pointer target, int flags);
    }

    private interface Gdi extends StdCallLibrary {
        Pointer CreateCompatibleDC(Pointer screen);
        Pointer CreateCompatibleBitmap(Pointer screen, int width, int height);
        Pointer SelectObject(Pointer dc, Pointer object);
        int GetPixel(Pointer dc, int x, int y);
        boolean DeleteObject(Pointer object);
        boolean DeleteDC(Pointer dc);
    }

    @Test void nativeFrameUsesWavecoveColors() throws Exception {
        assumeTrue(System.getProperty("os.name").contains("Windows 11"));
        assumeTrue(!Boolean.TRUE.equals(java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on")));
        EventQueue.invokeAndWait(() -> {
            var frame = new Frame("Wavecove");
            frame.setAutoRequestFocus(false);
            frame.setFocusableWindowState(false);
            frame.setBounds(-8000, -8000, 500, 200);
            try {
                frame.setVisible(true);
                var handle = Native.getWindowPointer(frame);
                WindowsTitleBar.applyColors(handle);
                assertEquals(0x201308, captionPixel(handle), "A barra deve usar o azul-marinho #081320 em COLORREF.");
            } finally {
                frame.dispose();
            }
        });
    }

    // DWM exposes caption color for writing only. Sample the rendered non-client area.
    private static int captionPixel(Pointer window) {
        var user = Native.load("user32", User.class);
        var gdi = Native.load("gdi32", Gdi.class);
        Pointer screen = user.GetWindowDC(window);
        Pointer dc = gdi.CreateCompatibleDC(screen);
        Pointer bitmap = gdi.CreateCompatibleBitmap(screen, 320, 40);
        Pointer original = gdi.SelectObject(dc, bitmap);
        try {
            assertTrue(user.PrintWindow(window, dc, 2));
            return gdi.GetPixel(dc, 180, 12);
        } finally {
            gdi.SelectObject(dc, original);
            gdi.DeleteObject(bitmap);
            gdi.DeleteDC(dc);
            user.ReleaseDC(window, screen);
        }
    }
}
