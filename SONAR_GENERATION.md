# CrossSave 26.04 声纳音效

本机 Python 3.14.7 可用，numpy、scipy、soundfile 均未安装。因此已按要求运行项目根目录的 `generate_sonar.py`，使用标准库生成 `sonar.wav`，没有联网或安装依赖。

WAV 为单声道、48 kHz、16 位 PCM，恰好五秒。为降低尖锐感，底层白噪音改为 180–1800 Hz，一千赫兹以上平滑衰减；在第 0、1、2、3、4 秒分别叠加 440 Hz 的柔和声纳滴声，每次长约 0.32 秒，起音延长到四十毫秒，衰减包络放缓。白噪音和滴声的增益同时降低，首尾二十毫秒淡入淡出减少循环接缝的爆音。

## 手动转成 OGG

在项目根目录打开终端。若已有 FFmpeg，可执行：

```powershell
ffmpeg -i .\sonar.wav -c:a libvorbis -q:a 5 .\src\main\resources\assets\crosssave\sounds\sonar.ogg
.\gradlew.bat --offline --no-daemon --max-workers 2 clean build
```

也可以用已有音频编辑器打开 `sonar.wav`，导出 **Vorbis OGG**，保存到 `src/main/resources/assets/crosssave/sounds/sonar.ogg`，再重新构建。不要仅把 WAV 扩展名改成 OGG。脚本已创建目标目录。

当前构建已加入使用者提供的 sonar.ogg，格式为 Vorbis、单声道、48 kHz、五秒，加载时会按 soundEnabled 设置循环播放。今后修改 WAV 后，须重新转换并替换该 OGG 再构建；音效文件缺失时仍静默跳过，其他功能照常运行。

## 重新生成

```powershell
python .\generate_sonar.py
```

脚本检查当前 Python 的 numpy、scipy、soundfile。如果三者均可用，使用 numpy 的随机白噪音、scipy 的频域滤波与 soundfile 的 Vorbis 导出直接生成 `sonar.ogg`。依赖不全时自动退回标准库 WAV，不调用安装命令。可用 `--wav-only` 强制生成 WAV。

## 注册与播放

- `CrossSaveSounds.java` 定义事件 `crosssave:sonar`，由 `sounds.json` 注册到客户端 SoundManager，文件路径为 `crosssave:sounds/sonar.ogg`。
- `sounds.json` 的事件键为 `sonar`。音频文件的 `name` 使用 `crosssave:sonar`，文件基本名仍为 `sonar`。Yarn 1.20.1 的 `Sound` 使用 `new Identifier(name)`；裸 `sonar` 会解析为 `minecraft:sonar`，因此这里必须带模组命名空间。
- 五秒声音设置 `stream: false`，使用静态缓冲和音频设备的循环播放。原版启动服务器时可能只渲染不执行客户端 tick，静态循环不依赖这些 tick 为流式缓冲续音。
- `SonarSoundInstance.java` 使用 1.20.1 的 `AbstractSoundInstance`，音量和音调均为 1.0，循环、无延迟、相对坐标、无距离衰减，类别为 MASTER；实际听感仍受游戏总音量影响。
- `TransferSound.java` 在加载条首次绘制时播放，并在客户端 tick、渲染帧和加载条绘制时维护同一个循环实例。原版退出世界或重载资源停止声音后，会在资源可用时恢复，避免叠加多个实例。
- `AutomaticWorldSwitcher` 在成功完成、保存失败及回滚转手动提示时直接停止声音。自动回滚过程中继续播放，原存档恢复完成后停止。客户端退出也会停止。
- 重试间隔只限制声音设备及资源恢复的检查频率，不控制转移进度或世界事件。
- 音效只在本地通过 SoundInstance 播放，因此不依赖服务器声音注册表或注册表解冻模块。没有新增依赖、网络请求或声音专用 Mixin。沿用原有 `GameRendererMixin` 的帧回调，不改变噪点、进度或世界切换节点。

## 配置

运行时配置路径基于 `MinecraftClient.getInstance().runDirectory`，位于游戏目录下的 `config/crosssave.json`。

```json
{
  "disclaimerAccepted": true,
  "key": "K",
  "noiseEnabled": true,
  "noiseManualOverride": false,
  "soundEnabled": true
}
```

`soundEnabled` 为布尔值，默认 true。旧配置升级时补充此字段，保留免责声明、快捷键、噪点及其他字段。K 键打开存档列表，然后点击“设置”；第二个原版条形按钮控制“加载音效”。完整写入配置后才更新按钮，写入失败保留原选择并显示翻译提示。关闭音效不影响噪点设置。

## 版本与构建

模组 ID 为 `crosssave`，Minecraft 固定 1.20.1，Java 17，版本字符串为 `26.04`。`build.gradle` 和 `gradle.properties` 与 26.03 完全相同；沿用上一版的 `settings.gradle` 最终版本覆盖方式，使输出文件与元数据均为 26.04。

构建仅进行离线编译和测试，不运行 Minecraft 客户端验证音频设备或游戏内行为。旧版本目录不修改。
