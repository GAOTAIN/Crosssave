# CrossSave 迁移维护规则

- Minecraft 固定 1.20.1，使用对应 Yarn 和 Java 17；所有代码注释用简体中文。
- 所有游戏数据路径从 MinecraftClient.getInstance().runDirectory 派生，禁止硬编码绝对路径。
- 玩家迁移是移走物品，不是复制物品。命令、界面和自动切换必须使用 TransferStore.saveAndRemove，不能仅调用 save 完成迁移。
- save 是底层 NBT 序列化接口；只有 NBT 完整写入、读取校验和所有槽位匹配成功后，saveAndRemove 才清空原存档主物品栏、盔甲和副手。
- 不改变目标原有主物品栏；装备替换、溢出掉落和经验取高规则保持不变，不处理末影箱和生物。
- 未消费的 pending 必须阻止再次迁移覆盖，防止丢失已移出的物品。
- 导入失败必须撤销目标侧的本次导入和掉落；自动回滚归还原物品前，先持久化取消待迁移记录。
- 26.01 保持手动切换；26.02 保持自动切换、进度和回滚；26.03 保持噪点与设置。
- 后续版本复制项目时保留这些规则、InventoryHandoff、OriginalPlayerState、TransferStore.saveAndRemove 和所有防复制测试。
- 修改迁移逻辑后必须运行完整 Gradle build，确认测试覆盖真实移出、写入失败不扣物品、回滚不重复导入和待迁移不可覆盖。不要只测试序列化副本。
- 目标导入成功先原子写入 consumed 标记，再清理文件；清理或聊天提示失败不能撤销已提交的物品。提交失败必须保留 NBT 并撤销目标临时变更。

- 26.04 加载音效为可选功能，缺少 sonar.ogg 时静默跳过；后续版本保留 soundEnabled、循环实例的重载恢复与所有终止路径的停止处理，不修改迁移事件。

- 26.05 每次启动进入主菜单后都必须显示免责声明；只有确认按钮可置 hasShownDisclaimerThisSession 为 true，disclaimerAccepted 仅保留历史记录。
- 物品快照、合并、掉落和回滚必须保留完整 NBT 并深拷贝；移出源槽位后清除原对象的 tag 与数量。不得只读取 Item ID 或截断容器嵌套内容。
- 嵌套物品校验须计入目标原有容器和本批次掉落；异常重做先撤销目标变更与掉落，二次失败沿用原有回滚，不提交损坏数据。
- 语言文件保持与 en_us.json 相同的键、占位符与命令，UTF-8 无 BOM；本版 40 种语言、每种 50 个键。
- 后续复制必须继承惰性归档命名 Crosssave-v${project.version}-mc1.20.1.jar，元数据标注实际模组版本与 Minecraft 兼容依赖；模组名称保持 Crosssave。
