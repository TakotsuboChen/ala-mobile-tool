package tools.alamobile.mod.util

/**
 * 车型目录：`carId` → 车型标识的单一事实源。
 *
 * ## 这份表从哪来
 *
 * 从 8.0.6 共存版的 Unity 资源里解析出来的，不是猜的：
 * - `ActiveAeroEra` → `activeaerocars.bundle` 的 22 个 `CarDefinition`
 * - `GroundEra_Cars` → `grounderacars_assets_all_*.bundle` 的 20 个 `CarDefinition`
 *
 * 每个 `CarDefinition` 提供 `carId` + `m_Name`（资产名）+ `teamReference`，
 * 顺着 `teamReference` 取到 `TeamDefinition` 的 `teamName`/`engineName`。
 * 生成脚本见 `.tools/join_car_team_table.py`。
 *
 * ## ⚠️ 关键事实：游戏里没有真实型号名
 *
 * 作者**只改了车队名和动力单元名，型号名根本不存在**。全 APK（72 个 assets
 * 逐字节扫描）精确搜索 `RB22` / `MCL40` / `AMR26` / `FW48` / `VF-26` /
 * `MAC-26` / `SF-26` **零命中**；`W17` / `A526` 的命中经字节上下文核对
 * 全部是二进制噪声或哈希串里的十六进制巧合（3~4 字符 token 在几十 MB
 * 文件里必然随机出现）。
 *
 * ⇒ 所以本表**只存游戏内真实存在的标识**（`carId` / 资产名 / 车队名），
 * 型号一列是**人工按车队+年代对应的外部知识**，不是游戏数据。若要展示，
 * 应明确标注来源，不要伪装成"游戏内名称"。
 *
 * ## 为什么用 carId 作主键
 *
 * 运行时唯一能稳定读到的车型标识就是 `carModifier.carUniqueID`（= `carId`，
 * 见 [tools.alamobile.mod.NativeBridge.pollCarIdentity]），配合 `carType`
 * 区分两代。资产名（`m_Name`）只在资源里，运行时从玩家车实例读不到。
 */
object CarCatalog {

    /** 车型年代（= 游戏内 `CarType` 枚举）。 */
    enum class Era(val gameValue: Int, val label: String) {
        /** `CarType.GroundEffectEra = 0` —— 2023 地效车。 */
        GROUND_EFFECT(0, "地效车"),

        /** `CarType.DoubleDRSEra = 1` —— 2026 主动空力车。 */
        DOUBLE_DRS(1, "主动空力车"),
        ;

        companion object {
            fun fromGameValue(v: Int): Era? = entries.firstOrNull { it.gameValue == v }
        }
    }

    /**
     * 单台车。
     *
     * @param carId 游戏内 `CarDefinition.carId`，运行时读 `carModifier.carUniqueID` 得到
     * @param assetName `CarDefinition.m_Name`（资源里的资产名，保留真实车队拼写，
     *   大小写按原样，例如 `mcLaren`/`alfaRomeo2`）
     * @param displayName 游戏内 `CarDefinition.displayName`（作者编码串，如 `mer_1_26`）
     * @param teamAssetName `TeamDefinition.m_Name`
     * @param teamName 游戏内 `TeamDefinition.teamName`（**虚构名**，作者改过）
     * @param engineName 游戏内 `TeamDefinition.engineName`（**虚构名**）
     * @param nationality ISO 3166 两位国家码（游戏内真实数据）
     * @param realModel **外部知识**，不是游戏数据。null = 该车队该年代无对应真实型号
     */
    data class Car(
        val carId: Int,
        val era: Era,
        val assetName: String,
        val displayName: String,
        val teamAssetName: String,
        val teamName: String,
        val engineName: String,
        val nationality: String,
        val realModel: String?,
    ) {
        /** 同一车队的两台车（1 号 / 2 号）。 */
        val isSecondCar: Boolean get() = carId % 2 == 1

        /** 车队展示用短名：优先真实车队名，回落游戏内虚构名。 */
        val teamLabel: String get() = realTeamName ?: teamName

        /**
         * 真实车队名（外部知识）。仅用于展示，**不参与上传**——
         * 上传只报 [carId] + [era]，由服务端按本表反查。
         */
        val realTeamName: String?
            get() = REAL_TEAM_NAMES[teamAssetName.lowercase()]
    }

    // ── 2026 主动空力（carType = 1，carId 20..41）──
    // 资源：activeaerocars.bundle
    private val ACTIVE_AERO = listOf(
        Car(20, Era.DOUBLE_DRS, "mercedes", "mer_1_26", "mercedes", "AFG Performance", "AFG P26 E-Performance", "de", "W17"),
        Car(21, Era.DOUBLE_DRS, "mercedes2", "mer_2_26", "mercedes", "AFG Performance", "AFG P26 E-Performance", "de", "W17"),
        Car(22, Era.DOUBLE_DRS, "ferrari", "fer_1_26", "ferrari", "Squadra Corse Carrari", "Carrari Tipo 266H", "it", "SF-26"),
        Car(23, Era.DOUBLE_DRS, "ferrari2", "fer_2_26", "ferrari", "Squadra Corse Carrari", "Carrari Tipo 266H", "it", "SF-26"),
        Car(24, Era.DOUBLE_DRS, "redbull", "redb_26_1", "redbull", "SeaGull Racing", "SGPT_H_02 V6", "at", "RB22"),
        Car(25, Era.DOUBLE_DRS, "redbull2", "redb_26_2", "redbull", "SeaGull Racing", "SGPT_H_02 V6", "at", "RB22"),
        Car(26, Era.DOUBLE_DRS, "astonMartin", "ast_26_1", "astonMartin", "Hawthorne Grand Prix", "AFG P26 E-Performance", "gb", "AMR26"),
        Car(27, Era.DOUBLE_DRS, "astonMartin2", "ast_26_2", "astonMartin", "Hawthorne Grand Prix", "AFG P26 E-Performance", "gb", "AMR26"),
        Car(28, Era.DOUBLE_DRS, "williams", "wil_26_1", "williams", "Maillans Motorsport", "AFG P26 E-Performance", "gb", "FW48"),
        Car(29, Era.DOUBLE_DRS, "williams2", "wil_26_2", "williams", "Maillans Motorsport", "AFG P26 E-Performance", "gb", "FW48"),
        Car(30, Era.DOUBLE_DRS, "mclaren", "mcl_26_1", "mclaren", "McBritain", "AFG P26 E-Performance", "gb", "MCL40"),
        Car(31, Era.DOUBLE_DRS, "mclaren2", "mcl_26_2", "mclaren", "McBritain", "AFG P26 E-Performance", "gb", "MCL40"),
        Car(32, Era.DOUBLE_DRS, "alpine", "alp_26_1", "alpine", "Bleu Horizon Racing", "AFG P26 E-Performance", "fr", "A526"),
        Car(33, Era.DOUBLE_DRS, "alpine2", "alp_26_2", "alpine", "Bleu Horizon Racing", "AFG P26 E-Performance", "fr", "A526"),
        // ⚠️ 资产名是 `vcarb`（不是贴图文件里的 `alt26`）——prefab 路径
        //    `ActiveAeroEra/CarsPrefabs/vcarb26_1.prefab` 实证。
        Car(34, Era.DOUBLE_DRS, "vcarb", "alt_26_1", "vcarb", "Scuderia Nova Dynamics ", "SGPT_H_02 V6", "it", "VCARB 03"),
        Car(35, Era.DOUBLE_DRS, "vcarb2", "alt_26_2", "vcarb", "Scuderia Nova Dynamics ", "SGPT_H_02 V6", "it", "VCARB 03"),
        Car(36, Era.DOUBLE_DRS, "audi", "aud_26_1", "audi", "Quantis Motorsport", "Vektor e-s1", "de", "R26"),
        Car(37, Era.DOUBLE_DRS, "audi2", "aud_26_2", "audi", "Quantis Motorsport", "Vektor e-s1", "de", "R26"),
        Car(38, Era.DOUBLE_DRS, "haas", "has_1_26", "haas", "IASS", "Carrari Tipo 266H", "us", "VF-26"),
        Car(39, Era.DOUBLE_DRS, "haas2", "has_2_26", "haas", "IASS", "Carrari Tipo 266H", "us", "VF-26"),
        Car(40, Era.DOUBLE_DRS, "cadillac", "cad_26_1", "cadillac", "Vanguard Performance", "Carrari Tipo 266H", "us", "MAC-26"),
        Car(41, Era.DOUBLE_DRS, "cadillac2", "cad_26_2", "cadillac", "Vanguard Performance", "Carrari Tipo 266H", "us", "MAC-26"),
    )

    // ── 2023 地效（carType = 0，carId 0..19）──
    // 资源：grounderacars_assets_all_*.bundle
    private val GROUND_EFFECT = listOf(
        Car(0, Era.GROUND_EFFECT, "mercedes", "mer_1_23", "mercedes", "AFG Team", "AFG Hybrid M18", "de", "W14"),
        Car(1, Era.GROUND_EFFECT, "mercedes2", "mer_2_23", "mercedes", "AFG Team", "AFG Hybrid M18", "de", "W14"),
        Car(2, Era.GROUND_EFFECT, "ferrari", "fer_1_23", "ferrari", "Carrari", "Carrari H18", "it", "SF-23"),
        Car(3, Era.GROUND_EFFECT, "ferrari2", "fer_2_23", "ferrari", "Carrari", "Carrari H18", "it", "SF-23"),
        Car(4, Era.GROUND_EFFECT, "redbull", "red_1_23", "redbull", "Sea Gull", "Wave Hybrid Spec4", "at", "RB19"),
        Car(5, Era.GROUND_EFFECT, "redbull2", "red_2_23", "redbull", "Sea Gull", "Wave Hybrid Spec4", "at", "RB19"),
        Car(6, Era.GROUND_EFFECT, "astonMartin", "ast_1_23", "astonMartin", "Sport Race", "AFG Hybrid M18", "gb", "AMR23"),
        Car(7, Era.GROUND_EFFECT, "astonMartin2", "ast_2_23", "astonMartin", "Sport Race", "AFG Hybrid M18", "gb", "AMR23"),
        Car(8, Era.GROUND_EFFECT, "williams", "wil_1_23", "williams", "Maillans", "AFG Hybrid M18", "gb", "FW45"),
        Car(9, Era.GROUND_EFFECT, "williams2", "wil_2_23", "williams", "Maillans", "AFG Hybrid M18", "gb", "FW45"),
        // ⚠️ 资产名大小写按原样（`mcLaren` / `mcLaren2`），不是笔误。
        Car(10, Era.GROUND_EFFECT, "mcLaren", "mcl_1_23", "mclaren", "McBritain", "AFG Hybrid M18", "gb", "MCL60"),
        Car(11, Era.GROUND_EFFECT, "mcLaren2", "mcl_2_23", "mclaren", "McBritain", "AFG Hybrid M18", "gb", "MCL60"),
        Car(12, Era.GROUND_EFFECT, "alpine", "alp_1_23", "alpine", "Focault", "Focault Y34", "fr", "A523"),
        Car(13, Era.GROUND_EFFECT, "alpine2", "alp_2_23", "alpine", "Focault", "Focault Y34", "fr", "A523"),
        Car(14, Era.GROUND_EFFECT, "alphaTauri", "alt_1_23", "alphaTauri", "Gabbiano", "Wave Hybrid Spec4", "it", "AT04"),
        Car(15, Era.GROUND_EFFECT, "alphaTauri2", "alt_2_23", "alphaTauri", "Gabbiano", "Wave Hybrid Spec4", "it", "AT04"),
        Car(16, Era.GROUND_EFFECT, "haas", "has_1_23", "haas", "IASS", "Carrari H18", "us", "VF-23"),
        Car(17, Era.GROUND_EFFECT, "haas2", "has_2_23", "haas", "IASS", "Carrari H18", "us", "VF-23"),
        Car(18, Era.GROUND_EFFECT, "alfaromeo", "alf_1_23", "alfaromeo", "Mauser", "Carrari H18", "ch", "C43"),
        Car(19, Era.GROUND_EFFECT, "alfaRomeo2", "alf_2_23", "alfaromeo", "Mauser", "Carrari H18", "ch", "C43"),
    )

    private val byId: Map<Int, Car> = (GROUND_EFFECT + ACTIVE_AERO).associateBy { it.carId }

    /**
     * 真实车队名（**外部知识，不是游戏数据**）。
     *
     * 键 = `TeamDefinition.m_Name` 小写。游戏内 `teamName` 全是作者虚构名，
     * 这张表把资产名映射回真实车队，仅供界面展示。
     *
     * ⚠️ **措辞由用户 2026-10-26 逐条给定，不得擅自改写**：
     * 特别是 `赛牛（红牛二队）` 与 `阿尔法托利（红牛二队）` 的括号写法、
     * `梅赛德斯-AMG` 的连字符，都是用户确认过的表述，不要"优化"成别的名字。
     *
     * 2026-10-26 变更：`梅赛德斯 AMG` → `梅赛德斯-AMG`（用户定案，连字符）。
     */
    private val REAL_TEAM_NAMES: Map<String, String> = mapOf(
        // 2026 主动空力
        "mercedes" to "梅赛德斯-AMG",
        "redbull" to "红牛",
        "ferrari" to "法拉利",
        "mclaren" to "迈凯伦",
        "alpine" to "阿尔派",
        "vcarb" to "赛牛（红牛二队）",
        "astonmartin" to "阿斯顿·马丁",
        "williams" to "威廉姆斯",
        "audi" to "奥迪",
        "haas" to "哈斯",
        "cadillac" to "凯迪拉克",
        // 2023 地效（mercedes/redbull/ferrari/mclaren/alpine/astonmartin/williams/haas 复用上表）
        "alphatauri" to "阿尔法托利（红牛二队）",
        "alfaromeo" to "阿尔法·罗密欧",
    )

    /** 全部 42 台车（按 carId 升序）。 */
    val all: List<Car> get() = (GROUND_EFFECT + ACTIVE_AERO).sortedBy { it.carId }

    /** 按 carId 查（null = 未知车，例如 DLC 新车或版本升级后新增）。 */
    fun byCarId(carId: Int): Car? = byId[carId]

    /** 按年代分组（用于界面展示）。 */
    fun byEra(era: Era): List<Car> = all.filter { it.era == era }

    /**
     * 上传用标识串：`"{carId}:{era}"`，例如 `"22:1"` = 2026 法拉利。
     * 未知车返回 null（上传侧据此省略该字段，而不是报一个假值）。
     */
    fun uploadKey(carId: Int): String? = byId[carId]?.let { "${it.carId}:${it.era.gameValue}" }
}
