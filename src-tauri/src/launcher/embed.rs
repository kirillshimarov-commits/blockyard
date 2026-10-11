//! Shows the game inside the launcher window instead of a separate one (Windows only).
//!
//! Once the game has opened its window, it loses its frame, becomes owned by the launcher window
//! and is laid exactly over its client area, following it when the launcher moves or resizes.
//! When the game exits its window disappears and the launcher underneath is visible again.

#[cfg(windows)]
mod imp {
    use std::sync::Mutex;
    use windows_sys::Win32::Foundation::{HWND, LPARAM, POINT, RECT};
    use windows_sys::Win32::Graphics::Gdi::ClientToScreen;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        EnumWindows, GetClassNameW, GetClientRect, GetWindow, GetWindowLongPtrW, GetWindowThreadProcessId, IsIconic,
        IsWindow, IsWindowVisible, SetForegroundWindow, SetWindowLongPtrW, SetWindowPos, GWLP_HWNDPARENT, GWL_EXSTYLE,
        GWL_STYLE, GW_OWNER, HWND_TOP, SWP_FRAMECHANGED, SWP_NOACTIVATE, SWP_NOZORDER, SWP_SHOWWINDOW, WS_CAPTION,
        WS_CHILD, WS_EX_APPWINDOW, WS_EX_CLIENTEDGE, WS_EX_DLGMODALFRAME, WS_EX_TOOLWINDOW, WS_EX_WINDOWEDGE,
        WS_MAXIMIZEBOX, WS_MINIMIZEBOX, WS_POPUP, WS_SYSMENU, WS_THICKFRAME, WS_VISIBLE,
    };

    /// Covered game windows with their original styles, as raw handles (HWND is a pointer and not Send).
    static EMBEDDED: Mutex<Vec<(isize, isize, isize)>> = Mutex::new(Vec::new());

    struct Search {
        pid: u32,
        found: HWND,
    }

    unsafe extern "system" fn visit(hwnd: HWND, lparam: LPARAM) -> i32 {
        let search = unsafe { &mut *(lparam as *mut Search) };
        let mut pid = 0u32;
        unsafe { GetWindowThreadProcessId(hwnd, &mut pid) };
        if pid != search.pid || unsafe { IsWindowVisible(hwnd) } == 0 || !unsafe { GetWindow(hwnd, GW_OWNER) }.is_null() {
            return 1;
        }
        let mut buf = [0u16; 64];
        let len = unsafe { GetClassNameW(hwnd, buf.as_mut_ptr(), buf.len() as i32) }.max(0) as usize;
        let class = String::from_utf16_lossy(&buf[..len]);
        // GLFW (1.13+) and LWJGL 2 (older versions) window classes.
        if class.starts_with("GLFW") || class.starts_with("LWJGL") {
            search.found = hwnd;
            return 0;
        }
        1
    }

    pub fn find_game_window(pid: u32) -> Option<isize> {
        let mut search = Search { pid, found: std::ptr::null_mut() };
        unsafe { EnumWindows(Some(visit), &mut search as *mut Search as LPARAM) };
        (!search.found.is_null()).then_some(search.found as isize)
    }

    /// Lays the game window exactly over the launcher's client area, in screen coordinates.
    fn fit(game: HWND, parent: HWND, flags: u32) {
        if unsafe { IsIconic(parent) } != 0 {
            return;
        }
        let mut rect = RECT { left: 0, top: 0, right: 0, bottom: 0 };
        let mut origin = POINT { x: 0, y: 0 };
        unsafe {
            GetClientRect(parent, &mut rect);
            ClientToScreen(parent, &mut origin);
            SetWindowPos(game, HWND_TOP, origin.x, origin.y, rect.right - rect.left, rect.bottom - rect.top, flags);
        }
    }

    /// Turns the game window into a borderless window owned by the launcher and covering it.
    /// A child window would not be drawn: the launcher's transparent WebView window is composited
    /// by DWM and does not show OpenGL child windows.
    pub fn attach(game: isize, parent: isize) {
        let (game, parent) = (game as HWND, parent as HWND);
        let (old_style, old_ex) = unsafe { (GetWindowLongPtrW(game, GWL_STYLE), GetWindowLongPtrW(game, GWL_EXSTYLE)) };
        unsafe {
            let style = (old_style as u32
                & !(WS_CHILD | WS_CAPTION | WS_THICKFRAME | WS_SYSMENU | WS_MINIMIZEBOX | WS_MAXIMIZEBOX))
                | WS_POPUP
                | WS_VISIBLE;
            SetWindowLongPtrW(game, GWL_STYLE, style as isize);
            // No taskbar button of its own: the launcher's button stands for both.
            let ex = (old_ex as u32 & !(WS_EX_APPWINDOW | WS_EX_WINDOWEDGE | WS_EX_CLIENTEDGE | WS_EX_DLGMODALFRAME))
                | WS_EX_TOOLWINDOW;
            SetWindowLongPtrW(game, GWL_EXSTYLE, ex as isize);
            // The owner keeps the game above the launcher and hides it when the launcher is minimized.
            SetWindowLongPtrW(game, GWLP_HWNDPARENT, parent as isize);
        }
        fit(game, parent, SWP_FRAMECHANGED | SWP_SHOWWINDOW);
        unsafe { SetForegroundWindow(game) };
        EMBEDDED.lock().unwrap().push((game as isize, old_style, old_ex));
    }

    fn alive() -> Vec<HWND> {
        let mut list = EMBEDDED.lock().unwrap();
        list.retain(|&(h, _, _)| unsafe { IsWindow(h as HWND) } != 0);
        list.iter().map(|&(h, _, _)| h as HWND).collect()
    }

    /// Call when the launcher window moves or changes size.
    pub fn resize_all(parent: isize) {
        for game in alive() {
            fit(game, parent as HWND, SWP_NOACTIVATE | SWP_SHOWWINDOW);
        }
    }

    /// Call when the launcher window is activated: the keyboard belongs to the game.
    pub fn focus() {
        if let Some(&game) = alive().last() {
            unsafe { SetForegroundWindow(game) };
        }
    }

    /// Gives every covered game its normal window back (before the launcher window goes away).
    pub fn detach_all() {
        let list: Vec<_> = EMBEDDED.lock().unwrap().drain(..).collect();
        for (h, style, ex) in list {
            let game = h as HWND;
            if unsafe { IsWindow(game) } == 0 {
                continue;
            }
            unsafe {
                SetWindowLongPtrW(game, GWLP_HWNDPARENT, 0);
                SetWindowLongPtrW(game, GWL_STYLE, style);
                SetWindowLongPtrW(game, GWL_EXSTYLE, ex);
                SetWindowPos(game, std::ptr::null_mut(), 80, 80, 1280, 720, SWP_NOZORDER | SWP_FRAMECHANGED | SWP_SHOWWINDOW);
            }
        }
    }
}

#[cfg(not(windows))]
mod imp {
    pub fn find_game_window(_pid: u32) -> Option<isize> {
        None
    }
    pub fn attach(_game: isize, _parent: isize) {}
    pub fn resize_all(_parent: isize) {}
    pub fn focus() {}
    pub fn detach_all() {}
}

pub use imp::*;

/// Waits for the game window of `pid` (up to 3 minutes while the game loads) and embeds it.
/// Returns whether it was embedded.
pub async fn embed_when_ready(pid: u32, parent: isize) -> bool {
    if !cfg!(windows) {
        return false;
    }
    for _ in 0..360 {
        if let Some(game) = find_game_window(pid) {
            // Let the game finish creating its GL context before the window is reparented.
            tokio::time::sleep(std::time::Duration::from_millis(400)).await;
            attach(game, parent);
            return true;
        }
        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
    }
    false
}
