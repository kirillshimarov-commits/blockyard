package app.blockyard.ui.mc;

import app.blockyard.ui.core.DialogView;
import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.HomeView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Add or edit a server: {@link DialogView} over the blurred home screen. */
public class ServerDialog extends UiScreen implements DialogView.Model {
    private static final ServerData.ServerPackStatus[] POLICIES = {
            ServerData.ServerPackStatus.ENABLED, ServerData.ServerPackStatus.DISABLED, ServerData.ServerPackStatus.PROMPT};

    private final HomeScreen parent;
    /** Index in the server list, or -1 to add a new server. */
    private final int index;
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder address = new StringBuilder();
    private int pack = 2;
    private int focus = 0;
    private final long openedAt = System.currentTimeMillis();

    public ServerDialog(HomeScreen parent, int index) {
        super(Component.literal(index < 0 ? "Новый сервер" : "Изменение сервера"));
        this.parent = parent;
        this.index = index;
        if (index >= 0) {
            ServerData s = parent.servers.get(index);
            name.append(s.name);
            address.append(s.ip);
            for (int i = 0; i < POLICIES.length; i++) if (POLICIES[i] == s.getResourcePackStatus()) pack = i;
        } else {
            name.append("Сервер Minecraft");
            focus = 1;
        }
    }

    @Override
    protected void init() {
        super.init();
        parent.init(width, height);
    }

    @Override
    protected void backdrop(GuiGraphicsExtractor ctx, McCanvas c, float delta) {
        parent.renderInert(ctx, delta);
        extractBlurredBackground(ctx);
        new McCanvas(ctx).fillScreen(0x59060510);
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
            ServerData s = parent.servers.get(index);
            s.name = n;
            s.ip = addr;
            s.setResourcePackStatus(POLICIES[pack]);
            s.setState(ServerData.State.INITIAL);
        } else {
            ServerData s = new ServerData(n, addr, ServerData.Type.OTHER);
            s.setResourcePackStatus(POLICIES[pack]);
            parent.servers.add(s, false);
        }
        parent.servers.save();
        minecraft.gui.setScreen(new HomeScreen(HomeView.MULTI));
    }

    private StringBuilder field() {
        return focus == 0 ? name : address;
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (input.codepoint() >= 32 && field().length() < 128) field().appendCodePoint(input.codepoint());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int keyCode = input.key();
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> onClose();
            case GLFW.GLFW_KEY_BACKSPACE -> {
                StringBuilder f = field();
                if (f.length() > 0) {
                    if (input.hasControlDown()) f.setLength(0);
                    else f.setLength(f.length() - 1);
                }
            }
            case GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> focus = 1 - focus;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (focus == 0) focus = 1;
                else save();
            }
            default -> {
                if (input.isPaste()) {
                    String clip = minecraft.keyboardHandler.getClipboard().replace("\n", "").replace("\r", "");
                    field().append(clip, 0, Math.min(clip.length(), Math.max(0, 128 - field().length())));
                } else if (input.isSelectAll()) {
                    field().setLength(0);
                }
            }
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    // The dialog view closes through its model; Screen calls it onClose in Mojang's names.
    public void close() {
        onClose();
    }
}
