package app.blockyard.ui.mc;

import net.minecraft.SharedConstants;
import net.minecraft.client.network.ServerInfo;

import java.util.Map;
import java.util.WeakHashMap;

/** 1.20.1 keeps no ping status on a server entry: it is tracked here, read from what the ping fills in. */
final class ServerStatus {
    enum State { INITIAL, PINGING, SUCCESSFUL, INCOMPATIBLE, UNREACHABLE }

    private static final Map<ServerInfo, State> STATES = new WeakHashMap<>();

    private ServerStatus() {}

    static State get(ServerInfo s) {
        State state = STATES.getOrDefault(s, State.INITIAL);
        if (state != State.PINGING) return state;
        if (s.online) {
            return s.protocolVersion == SharedConstants.getGameVersion().getProtocolVersion() ? State.SUCCESSFUL : State.INCOMPATIBLE;
        }
        // The pinger marks a failed connection with ping -1 (it starts at -2).
        return s.ping == -1L ? State.UNREACHABLE : State.PINGING;
    }

    static void set(ServerInfo s, State state) {
        STATES.put(s, state);
    }
}
