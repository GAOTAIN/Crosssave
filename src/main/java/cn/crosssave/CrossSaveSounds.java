package cn.crosssave;

import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class CrossSaveSounds {
    public static final Identifier SONAR_ID = new Identifier("crosssave", "sonar");
    public static final Identifier SONAR_RESOURCE = new Identifier("crosssave", "sounds/sonar.ogg");
    // sounds.json 由 1.20.1 的 SoundManager 注册客户端事件 crosssave:sonar。
    // 只通过 SoundInstance 在本地播放，无须把它加入用于服务器同步的全局声音注册表。
    // 客户端入口执行时原版注册表可能已经冻结，这样也不依赖额外的注册表解冻模块。
    public static final SoundEvent SONAR = SoundEvent.of(SONAR_ID);

    private CrossSaveSounds() { }
}
