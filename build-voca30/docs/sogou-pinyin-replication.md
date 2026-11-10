# 搜狗拼音输入法复刻报告（词库 + 常用语）

> 依据 `skills/replicate-software`（REA → reverse-skill → PentAGI），本报告只记录**实测证据**
> 与**已落地的复刻实现**；未观测项显式标注。

## 一、范围与指纹（P0）

| 项 | 值 |
| --- | --- |
| 目标制品 | `/Library/Input Methods/SogouInput.app` |
| Bundle ID | `com.sogou.inputmethod.sogou` |
| 版本 | `6.25.3.12004`（CFBundleShortVersionString 6.25.3） |
| 体积 | 524 MB（主二进制 153 MB） |
| 授权 | 本机已安装的商用输入法；仅作只读分析，不重分发原厂资源 |
| 分析工具 | `npx -y rea-agents@latest`（CLI 6.8.0，静态命令可用；Hopper/Ghidra/IDA/jadx 均缺失，未做原生反编译） |

## 二、证据链（P1）

### 2.1 内核词库资源

```
Contents/Resources/SogouImeKernelResources.bundle/SogouInput/   （75 个文件）
  sgim_sys.bin     4.2 MB  系统词库（魔数 cc dd ee ff）
  sgim_annex.bin   3.3 MB  附加词库（魔数 59 de 6e fc）
  sgim_py.bin       16 KB  拼音音节表（UTF-16 明文：a / ai / an / ang …）
  sgim_phrases.bin 4.4 KB  短语内核（无明文串，私有编码）
  sgim_quick.bin   417 KB  快捷/联想内核（私有编码）
  Fuzzy.dat        172 B   模糊音配置（UTF-16 明文）
```

`sgim_*.bin` 为搜狗私有容器，本机无对应解码引擎，**未做词条级提取**；
结论仅限「存在且按私有格式存储」，不臆测其内容。

### 2.2 模糊音配置（明文，可直接复刻）

`Fuzzy.dat` 全文（UTF-16LE，`iconv -f UTF-16LE`）：

```
[Gray]
zh=z   ch=c   sh=s
n=l    h=f    l=r
ang=an eng=en ing=in
iang=ian uang=uan
```

### 2.3 细胞词库（可解析）

```
~/Library/Application Support/Sogou/InputMethod/SogouPY/scd/4.scel      237,529 B
~/Library/Application Support/Sogou/InputMethod/SogouPY/scdInfo.plist
```

`scdInfo.plist` 声明：id=4「网络流行新词」7,926 条；id=14108「错读词汇」7,541 条（本机仅装了 id=4）。

`.scel` 结构实测：头部 `0x1540`（`40 15 00 00`）+ 签名 `ECS\x01`，`0x130` 起为 UTF-16LE 词库名，
`0x1540` 后为拼音音节表（438 个音节）与词条区（UTF-16LE 汉字 + 私有拼音索引）。
拼音索引编码未解出，**未复刻其索引字段**；词条按 UTF-16LE 汉字抽取。

### 2.4 常用语（自定义短语）

主二进制 `strings` 证据：

```
keyEnableUserPhrase / keyEnableSystemUserPhrase
-[SGPyConversionEngine setEnableUserPhrase:] / setSystemUserPhrase:
-[SGPyConversionEngine get_customPhrase_num] / getCustomPhraseNum
abbrPhrase            ← 常用语「快捷字母」
SGCustomPhraseSync    ← 常用语云同步
enPhraseDict
```

结论：搜狗拼音的「常用语」= **自定义短语 + 快捷字母（abbr）+ 云同步**，
与 iSpeech 现有「快捷短语（触发码/名称/正文/权重 + 快捷字母 + csBaby 同步）」同一模型。

## 三、复刻实现（P3/P4）

### 3.1 词库：细胞词库导入器（新增）

- 工具：`tools/import/import_sogou_scel.py`
- 输入：`scd/<id>.scel`
- 输出：iSpeech 分类词库文本（`# category:` + `code<TAB>词条<TAB>权重<TAB>cn`），
  可直接在「词典 → 添加词库」安装。
- 词条编码：优先反查 `pinyin86.dict`；未收录的新词用单字读音合成，
  多音字按「音节全局词频 + `PREFERRED_READINGS`」定音（`于适 → yu shi`、`阿坝州 → a ba zhou`）。
- 实测（`scd/4.scel`）：抽取 8,036 词条 → 写入 7,896（反查 2,359 / 合成 5,537），丢弃 140。
- 产物：`app/src/main/assets/sogou_cell_wangluo.txt`（7,898 行，208 KB，分类「网络流行语」）。

### 3.2 模糊音：补齐 `[Gray]` 预置

`app/src/main/java/com/ispeech/engine/PinyinEngine.kt`

| 搜狗 `[Gray]` | 改前 iSpeech | 改后 |
| --- | --- | --- |
| zh=z / ch=c / sh=s | 支持 | 支持 |
| n=l | 支持 | 支持 |
| h=f | **缺失** | 新增（h → f） |
| l=r | **缺失** | 新增（r → l，与 n=l 合成 n/l/r 等价类） |
| ang=an / eng=en / ing=in | 支持 | 支持 |
| iang=ian / uang=uan | 由去尾 g 规则隐式覆盖 | 显式覆盖（`iang`/`uang` 去尾 g） |

简拼侧同步：`fuzzyInitials` 由「n→l」扩展为「n/r→l、h→f」。

### 3.3 常用语：内置数据搬迁 + 用户导入

**内置常用语（新增）**：`sgim_phrases.bin` 实为 UTF-16LE 的「短语池 + 缩写表」，
可直接提取（诗词 / 网络常用语 + `chrdw`、`cqmyg`、`llysc`、`haodai`、`wocao`、`niubi` 等触发码）。

- 工具：`tools/import/import_sogou_quick_phrases.py`
- 产物：`app/src/main/assets/sogou_phrases.txt`（25 条：搜狗原码 5 / 首字母派生 20）
- 配对方式：每个汉字取**全部读音的首字母组合**与搜狗缩写表求交（如 汗∈{g,h}、土∈{c,d,t}
  组合出 `hdhxt`），命中即采用原码；配不上才派生，并在文件头标注来源，不臆造。
- 运行时：`QuickLocator` 把 `sogou_phrases.txt` 与 `quick_phrases.txt` 同作内置规则加载
  （`buildComposite` 与 `loadAll` 两条路径都已接入）。
- 触发码长度：五笔态组合缓冲上限 4 位，超过 4 位的码写成 `prefix` 模式——打前 4 位即可命中。

**用户常用语导入（新增）**：`tools/import/import_sogou_phrases.py`
把搜狗「常用语」导出的文本（`触发码<TAB>短语`、`短语<TAB>触发码`、逗号/空白分隔、`sb.什么`）
转成 iSpeech 快捷短语规则，可直接并入 `filesDir/quick_phrases_user.txt`。

**候选词池未导入**：`sgim_quick.bin`（3,786 条）是快捷**候选词**池，不是常用语；
若当短语导入会占「短语」档位、把普通拼音候选挤下去，故**不导入**，仅作证据记录。

### 3.4 词库：内置词库自动装配（新增）

`app/src/main/java/com/ispeech/data/BundledCellDicts.kt` + `assets/cell_dicts_manifest.txt`：

- App 启动时（`IspeechApp.onCreate` 的后台线程）读取清单，把随包分发的分类词库装入
  `CellDictStore`，装完 `invalidateEngine()` 让候选立刻生效。
- 幂等：同一份词库落到同一 `c1-` 散列 id，重复启动按覆盖处理；装过则跳过。
- 主线程红线：读 assets + 解析 + 落盘全部在后台线程，不占首帧。

## 四、复刻义务清单

| # | 类别 | 义务 | 原产品行为 | 证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| O-01 | 数据 | 支持导入搜狗细胞词库 | `scd/*.scel` 安装后并入候选 | `scdInfo.plist` + `4.scel` 解析 | 已复刻（本地文本词库导入；无词库商店） |
| O-02 | 数据 | 流行新词词条 | 7,926 条「网络流行新词」 | `4.scel` → 7,896 条 | 已复刻 |
| O-03 | 行为 | 模糊音预置 | `[Gray]` 11 组 | `Fuzzy.dat` | 已复刻（h=f、l=r 补齐） |
| O-04 | 行为 | 常用语 + 快捷字母 | `abbrPhrase` / `setUserPhrase:` | 主二进制 strings | 已复刻（快捷短语等价模型） |
| O-07 | 数据 | 内置常用语短语池 | 诗词 / 网络常用语 + 缩写表 | `sgim_phrases.bin` → 25 条 | 已复刻（`assets/sogou_phrases.txt`，原码 5 / 派生 20） |
| O-08 | 数据 | 用户常用语导入 | 搜狗常用语导出文本 | `import_sogou_phrases.py` | 已复刻（工具 + 格式自测） |
| O-09 | 行为 | 内置词库开机可用 | 细胞词库随输入法内置 | `BundledCellDicts` + 清单 | 已复刻（首启后台装配） |
| O-05 | 数据 | 系统词库（4.2 MB 私有格式） | `sgim_sys.bin` | 无解码引擎 | 未观测（缺工具） |
| O-06 | 数据 | 词库云同步 / 商店 | `SGCustomPhraseSync` 等 | strings 证据 | 未复刻（需后端） |

## 五、验证

```bash
# 词库导入器可重复运行（幂等：同名同内容 -> 同一 id）
python3 -I tools/import/import_sogou_scel.py \
  --input ~/Library/Application\ Support/Sogou/InputMethod/SogouPY/scd/4.scel \
  --output app/src/main/assets/sogou_cell_wangluo.txt \
  --name 网络流行新词 --category 网络流行语

# 静态/逻辑回归（见 README「证据链」）
for s in static input_logic t9 stats phrase_usage pinyin crosscut; do python3 tools/verify/check_$s.py; done
```

## 六、未闭环项

1. `.scel` 拼音索引字段未解出——新词读音靠单字合成，个别多音字仍可能与搜狗原音有偏差。
2. `sgim_phrases.bin` 的「触发码 ↔ 短语」映射索引未解出——已用「读音组合与缩写表求交」还原 5 条原码，
   其余 20 条为首字母派生（文件头已标注）。
3. `sgim_sys.bin`（4.2 MB 系统词库）与 `sgim_quick.bin`（候选词池）为私有编码：
   前者无解码器未提取；后者属候选词而非短语，导入会抢占拼音候选，故不导入。
4. 搜狗词库商店与词库云同步未复刻（需 csBaby 后端接口）。