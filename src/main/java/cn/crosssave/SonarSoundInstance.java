package cn.crosssave;

import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;

public final class SonarSoundInstance extends AbstractSoundInstance {
    public SonarSoundInstance() {
        super(CrossSaveSounds.SONAR, SoundCategory.MASTER, SoundInstance.createRandom());
        volume = 1.0F;
        pitch = 1.0F;
        // 五秒音频使用 sounds.json 的静态缓冲，由音频设备循环；原版启动服务器只渲染时也不会断音。
        repeat = true;
        repeatDelay = 0;
        relative = true;
        attenuationType = AttenuationType.NONE;
        // 声音位于听者坐标系原点，卸载期间没有世界或玩家也能播放，无距离衰减。
        x = y = z = 0;
    }

    @Override
    public boolean shouldAlwaysPlay() {
        return true;
    }
}
