//! Shows the game inside the launcher window instead of a separate one (Windows only).
//!
//! Once the game has opened its window, it is turned into a borderless child of the launcher
//! window and stretched over the whole client area. When the game exits its window disappears
//! and the launcher underneath is visible again.

#[cfg(windows)]
mod imp {
    use std::sync::Mutex;
    use windows_sys::Win32::Foundation::{HWND, LPARAM, RECT};
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::SetFocus;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        EnumWindows, GetClassNameW, GetClientRect, GetWindow, GetWindowLongPtrW, GetWindowThreadProcessId, IsWindow,
        IsWindowVisible, SetParent, SetWindowLongPtrW, SetWindowPos, GWL_EXSTYLE, GWL_STYLE, GW_OWNER, SWP_FRAMECHANGED,
        SWP_NOZORDER, SWP_SHOWWINDOW, WS_CAPTION, WS_CHILD, WS_EX_APPWINDOW, WS_EX_CLIENTEDGE, WS_EX_DLGMODALFRAME,
        WS_EX_WINDOWEDGE, WS_MAXIMIZEBOX, WS_MINIMIZEBOX, WS_POPUP, WS_SYSMENU, WS_THICKFRAME, WS_VISIBLE,
    };

    /// Embedded game windows, as raw handles (HWND is a pointer and not Send).
    static EMBEDDED: Mutex<Vec<isize>> = Mutex::new(Vec::new());

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

    fn fit(game: HWND, parent: HWND) {
        let mut rect = RECT { left: 0, top: 0, right: 0, bottom: 0 };
        unsafe {
            GetClientRect(parent, &mut rect);
            SetWindowPos(
                game,
                std::ptr::null_mut(),
                0,
                0,
                rect.right - rect.left,
                rect.bottom - rect.top,
                SWP_NOZORDER | SWP_FRAMECHANGED | SWP_SHOWWINDOW,
            );
        }
    }

    pub fn attach(game: isize, parent: isize) {
        let (game, parent) = (game as HWND, parent as HWND);
        unsafe {
            let style = GetWindowLongPtrW(game, GWL_STYLE) as u32;
            let style = (style & !(WS_POPUP | WS_CAPTION | WS_THICKFRAME | WS_SYSMENU | WS_MINIMIZEBOX | WS_MAXIMIZEBOX))
                | WS_CHILD
                | WS_VISIBLE;
            SetWindowLongPtrW(game, GWL_STYLE, style as isize);
            let ex = GetWindowLongPtrW(game, GWL_EXSTYLE) as u32;
            let ex = ex & !(WS_EX_APPWINDOW | WS_EX_WINDOWEDGE | WS_EX_CLIENTEDGE | WS_EX_DLGMODALFRAME);
            SetWindowLongPtrW(game, GWL_EXSTYLE, ex as isize);
            SetParent(game, parent);
        }
        fit(game, parent);
        unsafe { SetFocus(game) };
        EMBEDDED.lock().unwrap().push(game as isize);
    }

    fn alive() -> Vec<HWND> {
        let mut list = EMBEDDED.lock().unwrap();
        list.retain(|&h| unsafe { IsWindow(h as HWND) } != 0);
        list.iter().map(|&h| h as HWND).collect()
    }

    pub fn resize_all(parent: isize) {
        for game in alive() {
            fit(game, parent as HWND);
        }
    }

    pub fn focus() {
        if let Some(&game) = alive().last() {
            unsafe { SetFocus(game) };
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
