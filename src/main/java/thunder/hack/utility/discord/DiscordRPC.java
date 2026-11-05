package thunder.hack.utility.discord;

import com.sun.jna.Native;
import com.sun.jna.Library;

public interface DiscordRPC extends Library {
    DiscordRPC INSTANCE = load();

    /**
     * Tải thư viện native một cách an toàn. Trên nền tảng không có bản native phù hợp
     * (ví dụ iOS/Android arm64, hoặc thiếu file) thì trả về bản "rỗng" thay vì làm crash game.
     */
    private static DiscordRPC load() {
        try {
            return Native.load("discord-rpc", DiscordRPC.class);
        } catch (Throwable t) {
            System.err.println("[ThunderHack] DiscordRPC native library unavailable, RPC disabled: " + t.getMessage());
            return (DiscordRPC) java.lang.reflect.Proxy.newProxyInstance(
                    DiscordRPC.class.getClassLoader(),
                    new Class<?>[]{DiscordRPC.class},
                    (proxy, method, args) -> null);
        }
    }
    
    void Discord_UpdateHandlers(final DiscordEventHandlers p0);
    
    void Discord_UpdatePresence(final DiscordRichPresence p0);
    
    void Discord_Respond(final String p0, final int p1);
    
    void Discord_Register(final String p0, final String p1);
    
    void Discord_Shutdown();
    
    void Discord_UpdateConnection();
    
    void Discord_RegisterSteamGame(final String p0, final String p1);
    
    void Discord_RunCallbacks();
    
    void Discord_Initialize(final String p0, final DiscordEventHandlers p1, final boolean p2, final String p3);
    
    void Discord_ClearPresence();
                }
