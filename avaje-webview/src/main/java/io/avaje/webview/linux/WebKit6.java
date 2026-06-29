package io.avaje.webview.linux;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

final class WebKit6 {

    static final int WEBKIT_USER_CONTENT_INJECT_TOP_FRAME      = 1;
    static final int WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START = 0;

    static final FunctionDescriptor SCRIPT_MESSAGE_RECEIVED_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup WEBKIT_LIB =
            SymbolLookup.libraryLookup("libwebkitgtk-6.0.so.4", Arena.global());
    private static final SymbolLookup JSC_LIB =
            SymbolLookup.libraryLookup("libjavascriptcoregtk-6.0.so.1", Arena.global());
    private static final SymbolLookup LOOKUP = WEBKIT_LIB.or(JSC_LIB);

    static final MethodHandle WEBKIT_WEB_VIEW_NEW = downcall("webkit_web_view_new",
            FunctionDescriptor.of(ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_GET_USER_CONTENT_MANAGER =
            downcall("webkit_web_view_get_user_content_manager",
                    FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_GET_SETTINGS =
            downcall("webkit_web_view_get_settings",
                    FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_LOAD_URI =
            downcall("webkit_web_view_load_uri",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_LOAD_HTML =
            downcall("webkit_web_view_load_html",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_EVALUATE_JAVASCRIPT =
            downcall("webkit_web_view_evaluate_javascript",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG,
                            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_WEB_VIEW_GET_URI =
            downcall("webkit_web_view_get_uri",
                    FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_SETTINGS_SET_JS_CLIPBOARD =
            downcall("webkit_settings_set_javascript_can_access_clipboard",
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle WEBKIT_SETTINGS_SET_CONSOLE_TO_STDOUT =
            downcall("webkit_settings_set_enable_write_console_messages_to_stdout",
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle WEBKIT_SETTINGS_SET_DEV_EXTRAS =
            downcall("webkit_settings_set_enable_developer_extras",
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle WEBKIT_UCM_REGISTER_HANDLER =
            downcall("webkit_user_content_manager_register_script_message_handler",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_UCM_ADD_SCRIPT =
            downcall("webkit_user_content_manager_add_script",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_UCM_REMOVE_ALL_SCRIPTS =
            downcall("webkit_user_content_manager_remove_all_scripts",
                    FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle WEBKIT_USER_SCRIPT_NEW =
            downcall("webkit_user_script_new",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle WEBKIT_USER_SCRIPT_UNREF =
            downcall("webkit_user_script_unref",
                    FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle JSC_VALUE_TO_STRING =
            downcall("jsc_value_to_string",
                    FunctionDescriptor.of(ADDRESS, ADDRESS));

    private static MethodHandle downcall(String sym, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(sym).orElseThrow(() -> new UnsatisfiedLinkError("WebKit6 symbol not found: " + sym)),
                desc);
    }

    static MemorySegment webkitWebViewNew() {
        try { return (MemorySegment) WEBKIT_WEB_VIEW_NEW.invokeExact(); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static MemorySegment webkitWebViewGetUserContentManager(MemorySegment wv) {
        try { return (MemorySegment) WEBKIT_WEB_VIEW_GET_USER_CONTENT_MANAGER.invokeExact(wv); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static MemorySegment webkitWebViewGetSettings(MemorySegment wv) {
        try { return (MemorySegment) WEBKIT_WEB_VIEW_GET_SETTINGS.invokeExact(wv); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void webkitWebViewLoadUri(MemorySegment wv, MemorySegment uri) {
        try { WEBKIT_WEB_VIEW_LOAD_URI.invokeExact(wv, uri); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void webkitWebViewLoadHtml(MemorySegment wv, MemorySegment html, MemorySegment baseUri) {
        try { WEBKIT_WEB_VIEW_LOAD_HTML.invokeExact(wv, html, baseUri); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void webkitWebViewEvaluateJavascript(MemorySegment wv, MemorySegment js, long length) {
        try {
            WEBKIT_WEB_VIEW_EVALUATE_JAVASCRIPT.invokeExact(
                    wv, js, length,
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    static MemorySegment webkitWebViewGetUri(MemorySegment wv) {
        try { return (MemorySegment) WEBKIT_WEB_VIEW_GET_URI.invokeExact(wv); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void webkitSettingsSetJsClipboard(MemorySegment settings, boolean enable) {
        try { WEBKIT_SETTINGS_SET_JS_CLIPBOARD.invokeExact(settings, enable ? 1 : 0); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void webkitSettingsSetConsoleToStdout(MemorySegment settings, boolean enable) {
        try { WEBKIT_SETTINGS_SET_CONSOLE_TO_STDOUT.invokeExact(settings, enable ? 1 : 0); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void webkitSettingsSetDevExtras(MemorySegment settings, boolean enable) {
        try { WEBKIT_SETTINGS_SET_DEV_EXTRAS.invokeExact(settings, enable ? 1 : 0); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void webkitUcmRegisterHandler(MemorySegment manager, MemorySegment name) {
        try { int _ = (int) WEBKIT_UCM_REGISTER_HANDLER.invokeExact(manager, name, MemorySegment.NULL); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void webkitUcmAddScript(MemorySegment manager, MemorySegment script) {
        try { WEBKIT_UCM_ADD_SCRIPT.invokeExact(manager, script); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void webkitUcmRemoveAllScripts(MemorySegment manager) {
        try { WEBKIT_UCM_REMOVE_ALL_SCRIPTS.invokeExact(manager); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static MemorySegment webkitUserScriptNew(MemorySegment source, int injectedFrames, int injectionTime) {
        try {
            return (MemorySegment) WEBKIT_USER_SCRIPT_NEW.invokeExact(
                    source, injectedFrames, injectionTime,
                    MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void webkitUserScriptUnref(MemorySegment script) {
        try { WEBKIT_USER_SCRIPT_UNREF.invokeExact(script); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static String jscValueToString(MemorySegment jscValue) {
        MemorySegment raw;
        try { raw = (MemorySegment) JSC_VALUE_TO_STRING.invokeExact(jscValue); }
        catch (Throwable t) { throw new RuntimeException(t); }
        String s = raw.reinterpret(Long.MAX_VALUE).getString(0);
        GLib.gFree(raw);
        return s;
    }

    private WebKit6() {}
}
