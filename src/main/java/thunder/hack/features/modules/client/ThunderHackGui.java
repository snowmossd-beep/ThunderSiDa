package thunder.hack.features.modules.client;

import thunder.hack.gui.thundergui.ThunderGui;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

import java.awt.*;

public final class ThunderHackGui extends Module {
    // Màu cố định: trắng trong suốt (đã xóa phần chỉnh màu)
    public static final Color ON_COLOR_1 = new Color(255, 255, 255, 70);
    public static final Color ON_COLOR_2 = new Color(255, 255, 255, 38);
    public static final Setting<Float> scrollSpeed = new Setting<>("ScrollSpeed", 1f, 0.1F, 2.0F);

    public ThunderHackGui() {
        super("ThunderGui", Category.CLIENT);
    }

    @Override
    public void onEnable() {
        mc.setScreen(ThunderGui.getThunderGui());
        disable();
    }

    public static Color getColorByTheme(int id) {
        return switch (id) {
            case 0 -> new Color(255, 255, 255, 46);   // Основная плита (kính trắng trong suốt)
            case 1 -> new Color(255, 255, 255, 64);   // плита лого
            case 2 -> new Color(-1);                  // chữ THUNDERHACK, icon trắng
            case 3, 8 -> new Color(255, 255, 255, 170); // версия
            case 4 -> new Color(255, 255, 255, 38);   // плита под категориями
            case 5 -> new Color(255, 255, 255, 92);   // выбор режима (вкл)
            case 6 -> new Color(255, 255, 255, 80);   // разделитель
            case 7 -> new Color(255, 255, 255, 34);   // плита настроек
            case 9 -> new Color(255, 255, 255, 38);
            default -> new Color(255, 255, 255, 46);
        };
    }
}
