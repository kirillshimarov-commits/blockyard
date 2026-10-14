package app.blockyard.ui.mc;

import app.blockyard.ui.core.DialogView;
import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.HomeView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Add or edit a server: {@link DialogView} over the blurred home screen. */
public class ServerDialog extends UiScreen implements DialogView.Model {
    private static final ServerInfo.ResourcePackPolicy[] POLICIES = {
            ServerInfo.ResourcePackPolicy.ENABLED, ServerInfo.ResourcePackPolicy.DISABLED, ServerInfo.ResourcePackPolicy.PROMPT};

    private final HomeScreen parent;
    /** Index in the server list, or -1 to add a new server. */
    private final int index;
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder address = new StringBuilder();
    private int pack = 2;
    private int focus = 0;
    private final long openedAt = System.currentTimeMillis();

    public ServerDialog(HomeScreen parent, int index) {
        super(Text.literal(index < 0 ? "Новый сервер" : "Изменение сервера"));
        this.parent = parent;
        this.index = index;
        if (index >= 0) {
            ServerInfo s = parent.servers.get(index);
            name.append(s.name);
            address.append(s.address);
            for (int i = 0; i < POLICIES.length; i++) if (POLICIES[i] == s.getResourcePackPolicy()) pack = i;
        } else {
            name.append("Сервер Minecraft");
            focus = 1;
        }
    }

    @Override
    protected void init() {
        super.init();
        parent.init(client, width, height);
    }

    @Override
    protected void backdrop(DrawContext ctx, McCanvas c, float delta) {
        parent.renderInert(ctx, delta);
        new McCanvas(ctx).fillScreen(0x8C060510);
    }

    @Override
    protected void draw(Frame f, float delta) {
        float a = HomeView.ease((System.currentTimeMillis() - openedAt) / 220f);
        DialogView.draw(f, this, a);
    }

    public String title() {
        return getTitle().getString();
    }

    public String name() {
        return name.toString();
    }

    public String address() {
        return address.toString();
    }

    public int pack() {
        return pack;
    }

    public void pack(int i) {
        pack = i;
    }

    public int focus() {
        return focus;
    }

    public void focus(int i) {
        focus = i;
    }

    public boolean canSave() {
        return !address.toString().isBlank();
    }

    public void save() {
        if (!canSave()) return;
        String n = name.toString().isBlank() ? "Сервер Minecraft" : name.toString().trim();
        String addr = address.toString().trim();
        if (index >= 0) {
            ServerInfo s = parent.servers.get(index);
            s.name = n;
            s.address = addr;
            s.setResourcePackPolicy(POLICIES[pack]);
            ServerStatus.set(s, ServerStatus.State.INITIAL);
        } else {
            ServerInfo s = new ServerInfo(n, addr, ServerInfo.ServerType.OTHER);
            s.setResourcePackPolicy(POLICIES[pack]);
            parent.servers.add(s, false);
        }
        parent.servers.saveFile();
        client.setScreen(new HomeScreen(HomeView.MULTI));
    }

    private StringBuilder field() {
        return focus == 0 ? name : address;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (chr >= ' ' && field().length() < 128) field().append(chr);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> close();
            case GLFW.GLFW_KEY_BACKSPACE -> {
                StringBuilder f = field();
                if (f.length() > 0) {
                    if (Screen.hasControlDown()) f.setLength(0);
                    else f.setLength(f.length() - 1);
                }
            }
            case GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> focus = 1 - focus;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (focus == 0) focus = 1;
                else save();
            }
            default -> {
                if (Screen.isPaste(keyCode)) {
                    String clip = client.keyboard.getClipboard().replace("\n", "").replace("\r", "");
                    field().append(clip, 0, Math.min(clip.length(), Math.max(0, 128 - field().length())));
                } else if (Screen.isSelectAll(keyCode)) {
                    field().setLength(0);
                }
            }
        }
        return true;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
