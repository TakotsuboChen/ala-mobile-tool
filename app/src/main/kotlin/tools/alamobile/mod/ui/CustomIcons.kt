package tools.alamobile.mod.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 多 path SVG → ImageVector helper。与 OverviewPage.kt 的 svgIcon() 逻辑相同，
// 但支持多条 path，每条可独立配置 fill/stroke/pathFillType。
// PathParser 直接吃 SVG d 字符串（含 M/m/c/a/z 全命令），转成 PathNode 列表，
// 再在 path() DSL 的 PathBuilder lambda 里按 node 类型分发——零手动转译。
// path 数据由 Inkscape + svgpathtools + svgo 三步流水线从原始 SVG
// （含 mask/clipPath/transform/text）flatten 为纯 path。
private data class SvgPath(
    val d: String,
    val fill: SolidColor? = SolidColor(Color.Black),
    val stroke: SolidColor? = null,
    val strokeWidth: Float = 0f,
    val strokeLineCap: StrokeCap = StrokeCap.Butt,
    val strokeLineJoin: StrokeJoin = StrokeJoin.Miter,
    val pathFillType: PathFillType = PathFillType.NonZero
)

private fun svgIconMulti(
    name: String,
    viewportWidth: Float,
    viewportHeight: Float,
    paths: List<SvgPath>,
    // intrinsic 尺寸。**默认 24×24 只适用于近似正方形的 viewport**：本函数把
    // viewport 映射到 defaultWidth×defaultHeight，两者比例不一致会导致非等比
    // 拉伸。扁长图形（如 BoostIcon 的 90×48.49）必须按同比例传默认尺寸。
    defaultWidth: Dp = 24.dp,
    defaultHeight: Dp = 24.dp,
    // 源 SVG 的 viewBox 原点（四元组的前两位）。**Compose 的 ImageVector
    // 不支持非零 viewport 原点**（viewportWidth/Height 只定义 0..W × 0..H），
    // 带偏移的 viewBox（如 `5.28 12.57 79.37 64.82`）必须把图形平移回
    // 0..W × 0..H，否则超出部分被裁掉（BoostIcon 曾实测"只显示一半"）。
    //
    // ⚠️ 平移用 `group(translationX/Y)` 而非改写 path 数据：group 变换是
    // Compose 原生支持的语义，与 SVG `<g transform="translate(...)">` 逐位
    // 等价——可用 `rsvg-convert` 渲染包一层 `<g>` 的参考图做像素对拍验证
    //（实测 AE=0）。改写 path 数据则要手工处理绝对/相对命令、首个 moveto
    // 的绝对化，且难以独立验证（Vibration.svg 的实测教训）。
    viewportOriginX: Float = 0f,
    viewportOriginY: Float = 0f
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = defaultWidth,
        defaultHeight = defaultHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight
    ).apply {
        // 非零原点时才包一层 group（原点为零时保持原有零包装结构，
        // 不受此参数引入的任何新语义影响——既有图标全部走这条零路径）。
        val wrap = viewportOriginX != 0f || viewportOriginY != 0f
        if (wrap) {
            group(
                translationX = -viewportOriginX,
                translationY = -viewportOriginY
            ) { emitPaths(paths) }
        } else {
            emitPaths(paths)
        }
    }.build()

/** 把 [paths] 逐条写进当前 ImageVector 作用域（供 group 内外复用）。 */
private fun ImageVector.Builder.emitPaths(paths: List<SvgPath>) {
    paths.forEach { p ->
        path(
            fill = p.fill,
            stroke = p.stroke,
            strokeLineWidth = p.strokeWidth,
            strokeLineCap = p.strokeLineCap,
            strokeLineJoin = p.strokeLineJoin,
            pathFillType = p.pathFillType
        ) {
            PathParser().parsePathString(p.d).toNodes().forEach { node ->
                when (node) {
                    is PathNode.MoveTo -> moveTo(node.x, node.y)
                    is PathNode.LineTo -> lineTo(node.x, node.y)
                    is PathNode.RelativeMoveTo -> moveToRelative(node.dx, node.dy)
                    is PathNode.RelativeLineTo -> lineToRelative(node.dx, node.dy)
                    is PathNode.HorizontalTo -> horizontalLineTo(node.x)
                    is PathNode.VerticalTo -> verticalLineTo(node.y)
                    is PathNode.RelativeHorizontalTo -> horizontalLineToRelative(node.dx)
                    is PathNode.RelativeVerticalTo -> verticalLineToRelative(node.dy)
                    is PathNode.CurveTo -> curveTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
                    is PathNode.RelativeCurveTo -> curveToRelative(node.dx1, node.dy1, node.dx2, node.dy2, node.dx3, node.dy3)
                    is PathNode.QuadTo -> quadTo(node.x1, node.y1, node.x2, node.y2)
                    is PathNode.RelativeQuadTo -> quadToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
                    is PathNode.ReflectiveCurveTo -> reflectiveCurveTo(node.x1, node.y1, node.x2, node.y2)
                    is PathNode.RelativeReflectiveCurveTo -> reflectiveCurveToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
                    is PathNode.ReflectiveQuadTo -> reflectiveQuadTo(node.x, node.y)
                    is PathNode.RelativeReflectiveQuadTo -> reflectiveQuadToRelative(node.dx, node.dy)
                    is PathNode.ArcTo -> arcTo(node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta, node.isMoreThanHalf, node.isPositiveArc, node.arcStartX, node.arcStartY)
                    is PathNode.RelativeArcTo -> arcToRelative(node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta, node.isMoreThanHalf, node.isPositiveArc, node.arcStartDx, node.arcStartDy)
                    is PathNode.Close -> close()
                }
            }
        }
    }
}

// ─── 牵引力控制（TC）───
// 车身 + 侧滑线，flatten 后单 path（fill=currentColor）。
val TcIcon: ImageVector = svgIconMulti(
    "TcIcon", 24f, 24f,
    listOf(SvgPath(d = "M7.2 2 4.5 6.5h15L16.8 2zM1.5 3.8 1 5.2l3.2 1.3.3-1.7zM22.5 3.8l-3 1 .3 1.7L23 5.2zM2.5 6.5v6c0 .5.5 1 1 1h3c.5 0 1-.5 1-1v-1.3h9v1.3c0 .5.5 1 1 1h3c.5 0 1-.5 1-1v-6zM3.85 14.8c0 .178-.017.194-.102.304s-.276.273-.55.486c-.276.212-.634.472-.962.894A2.86 2.86 0 0 0 1.65 18.2c0 .62.292 1.2.618 1.553.325.353.656.55.906.713s.42.29.469.344l.007.005c0 .325-.043.278-.437.647-.4.375-1.162 1.18-1.162 2.34H4.35c0-.341.037-.287.437-.662S5.95 21.959 5.95 20.8c0-.62-.291-1.2-.617-1.553s-.656-.55-.906-.713-.42-.29-.469-.344c-.004-.004-.005-.003-.008-.005.001-.162.022-.184.104-.29.085-.109.276-.273.55-.486.276-.212.634-.472.961-.894A2.86 2.86 0 0 0 6.15 14.8zM16.85 14.8c0 .178-.017.194-.102.304s-.276.273-.55.486c-.276.212-.634.472-.962.894a2.86 2.86 0 0 0-.586 1.715c0 .62.292 1.2.618 1.553.325.353.656.55.906.713s.42.29.469.344l.007.005c0 .325-.043.278-.437.647-.4.375-1.162 1.18-1.162 2.34h2.299c0-.341.037-.287.437-.662s1.162-1.18 1.162-2.338c0-.62-.291-1.2-.617-1.553s-.656-.55-.906-.713-.42-.29-.469-.344c-.004-.004-.005-.003-.008-.005.001-.162.022-.184.104-.29.085-.109.276-.273.55-.486.276-.212.634-.472.961-.894a2.86 2.86 0 0 0 .586-1.715z"))
)

// ─── 防抱死（ABS）───
// 中心圆（stroke 2.4）+ 两侧弧线（stroke 2.4, round cap）。
// 去掉 "ABS" 文字——Inkscape text-to-path 产出的 centerline path 在 24dp
// 图标尺寸下无法清晰渲染（fill 变 blob、stroke 也粘连），直接省略。
val AbsIcon: ImageVector = svgIconMulti(
    "AbsIcon", 24f, 24f,
    listOf(
        SvgPath(
            d = "M19 12a7 7 0 0 1-7 7 7 7 0 0 1-7-7 7 7 0 0 1 7-7 7 7 0 0 1 7 7",
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeWidth = 2.4f
        ),
        SvgPath(
            d = "M3.8 6a9.2 9.2 0 0 0 0 12M20.2 6a9.2 9.2 0 0 1 0 12",
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeWidth = 2.4f,
            strokeLineCap = StrokeCap.Round
        )
    )
)

// ─── 线性踏板 ───
// 刹车踏板（3 槽）+ 油门踏板（5 槽），flatten 后单 path（fill=currentColor）。
val PedalsIcon: ImageVector = svgIconMulti(
    "PedalsIcon", 24f, 24f,
    listOf(SvgPath(d = "M6.5 2v4.5h2.2V2zM3.3 6.5a.8.8 0 0 0-.8.8v6.9a.8.8 0 0 0 .8.8h8.6a.8.8 0 0 0 .8-.8V7.3c0-.442-.356-.8-.8-.8zM17.4 2v4.5h2.2V2zM16 6.5a.8.8 0 0 0-.8.8v13.9a.8.8 0 0 0 .8.8h5a.8.8 0 0 0 .8-.8V7.3a.8.8 0 0 0-.8-.8z"))
)

// ─── 手动换挡 ───
// 两个齿轮，圆心连线在 45° 对角线上：(7.5,7.5) 和 (16.5,16.5)。
// 渲染分两条 path：(1) 环路径 = 4 个圆（2 外圆 + 2 中心孔），evenOdd 填充——
// 外圆减内圆 = 环形，孔洞区域被 evenOdd 自动挖空（2 层 = 偶 = 空）。
// (2) 齿帽路径 = 16 个梯形（每齿 2 帽，2 齿轮 × 4 齿 × 2），NonZero 填充——
// 齿帽只含圆外部分（内边在圆上），不覆盖中心孔，所以不影响挖洞。
// 齿帽顶点由 Python 三角函数预计算旋转坐标 + 圆-矩形交点裁剪。
val GearboxIcon: ImageVector = svgIconMulti(
    "GearboxIcon", 24f, 24f,
    listOf(
        SvgPath(
            d = "M3.0000,7.5000a4.5000,4.5000 0 1 0 9.0000,0a4.5000,4.5000 0 1 0 -9.0000,0zM5.5000,7.5000a2.0000,2.0000 0 1 0 4.0000,0a2.0000,2.0000 0 1 0 -4.0000,0zM12.5000,16.5000a4.0000,4.0000 0 1 0 8.0000,0a4.0000,4.0000 0 1 0 -8.0000,0zM14.9000,16.5000a1.6000,1.6000 0 1 0 3.2000,0a1.6000,1.6000 0 1 0 -3.2000,0z",
            pathFillType = PathFillType.EvenOdd
        ),
        SvgPath(
            d = "M11.8635,6.4000L13.0000,6.4000L13.0000,8.6000L11.8635,8.6000zM3.1365,6.4000L2.0000,6.4000L2.0000,8.6000L3.1365,8.6000zM11.3633,9.8076L12.1669,10.6113L10.6113,12.1669L9.8076,11.3633zM5.1924,3.6367L4.3887,2.8331L2.8331,4.3887L3.6367,5.1924zM8.6000,11.8635L8.6000,13.0000L6.4000,13.0000L6.4000,11.8635zM8.6000,3.1365L8.6000,2.0000L6.4000,2.0000L6.4000,3.1365zM5.1924,11.3633L4.3887,12.1669L2.8331,10.6113L3.6367,9.8076zM11.3633,5.1924L12.1669,4.3887L10.6113,2.8331L9.8076,3.6367zM20.4609,17.0582L21.2711,17.3939L20.5057,19.2416L19.6955,18.9060zM13.3045,14.0940L12.4943,13.7584L11.7289,15.6061L12.5391,15.9418zM18.9060,19.6955L19.2416,20.5057L17.3939,21.2711L17.0582,20.4609zM15.9418,12.5391L15.6061,11.7289L13.7584,12.4943L14.0940,13.3045zM15.9418,20.4609L15.6061,21.2711L13.7584,20.5057L14.0940,19.6955zM18.9060,13.3045L19.2416,12.4943L17.3939,11.7289L17.0582,12.5391zM13.3045,18.9060L12.4943,19.2416L11.7289,17.3939L12.5391,17.0582zM20.4609,15.9418L21.2711,15.6061L20.5057,13.7584L19.6955,14.0940z"
        )
    )
)

// ─── 刹车响应曲线 ───
// 刹车盘轮廓 + 外圆 + 4 个卡钳点。flatten 后 3 条 path。viewport 470×462。
val BrakeCurveIcon: ImageVector = svgIconMulti(
    "BrakeCurveIcon", 470f, 462f,
    listOf(
        SvgPath(d = "M248.355.096C245.8.063 242.551.075 238.301.1 228.5.2 217.1.8 213 1.5c-51.3 8.7-97.9 32.4-135.7 68.8C61.4 85.7 53 95.7 41.6 112.8c-18.9 28.3-31.2 57.4-38.7 91.7-2.9 13.6-4 49.5-1.5 54.2C6.9 269.5 18.7 276 32.6 276c6 0 5.7-.4 7.4 10.5 1.4 8.6 6.1 25.6 9.9 36.1 9.7 26.5 25.3 51.1 45.3 72 23.1 24 47.5 40.3 78.4 52.4 29.2 11.4 67.4 16.3 99 12.6 88.8-10.3 160.2-72.3 182.8-158.6 4.7-18.1 6.1-29.9 6-51.5-.2-37.5-6.6-65-22.9-96.8-30.2-59.5-85.3-100.5-151.4-112.8-5.8-1-10.6-1.9-10.8-1.9s-.3-3.4-.3-7.5c0-12.8-5.6-23.2-15.1-28C257.45.7 256.024.194 248.355.096M236.5 37.5l.8 5c.4 2.7.4 11.4.1 19.2l-.6 14.2-2.7.5c-1.4.3-4.6.8-7.1 1.1-7.2 1.1-23 5.2-32.6 8.6-48.8 17.1-89.4 57.4-107.9 107.2-3.5 9.5-7.8 25.7-9 33.7-.3 2.5-.8 5.7-1.1 7.2l-.6 2.8H56.4c-18.1 0-19.5-.1-19-1.8.2-.9.8-4.6 1.1-8.2.9-8.5 4.8-25.9 8.7-37.9C66.5 128.2 112.9 77.9 172 53.6c19.6-8.1 36-12.5 55-14.9zM276.6 77c.3 0 3.7.7 7.7 1.6 51.6 11.3 93.5 42.5 118.1 87.9 26.8 49.1 27.4 112.1 1.7 161-20.7 39.5-55.1 70-94.9 84-22 7.8-37.6 10.5-60.7 10.5-54.8 0-105.4-25.3-138.6-69.5-14.7-19.5-26.1-44.4-30.8-67.3l-1.8-9 6.7-.4c12.2-.9 22.7-7.7 27-17.6 1.1-2.3 2.6-9.5 3.5-16 4.4-33.5 15.2-58 35.5-80.7 24.9-27.7 55.6-43.1 95.1-47.5 11.6-1.3 18.3-4.3 23.4-10.6 4.9-6 7.5-13 7.5-20.4 0-3.3.2-6 .6-6"),
        SvgPath(d = "M248.83 132.6c-63.628 0-115.62 51.992-115.62 115.62 0 63.63 51.992 115.62 115.62 115.62s115.62-51.99 115.62-115.62c0-63.628-51.991-115.62-115.62-115.62m0 38.26c42.951 0 77.36 34.41 77.36 77.36 0 42.952-34.409 77.36-77.36 77.36s-77.36-34.408-77.36-77.36c0-42.95 34.409-77.36 77.36-77.36"),
        SvgPath(d = "M248.83 190.46c-10.576 0-19.15 8.574-19.15 19.15s8.574 19.15 19.15 19.15c10.577 0 19.15-8.574 19.15-19.15s-8.574-19.15-19.15-19.15M287.44 229.07c-10.577 0-19.151 8.574-19.15 19.15 0 10.577 8.574 19.15 19.15 19.15s19.149-8.573 19.15-19.15-8.574-19.15-19.15-19.15M248.83 267.68c-10.576 0-19.15 8.574-19.15 19.15 0 10.577 8.574 19.15 19.15 19.15 10.577 0 19.15-8.573 19.15-19.15s-8.573-19.15-19.15-19.15M210.22 229.07a19.15 19.15 0 0 0-19.15 19.15 19.15 19.15 0 0 0 19.15 19.15 19.15 19.15 0 0 0 19.15-19.15 19.15 19.15 0 0 0-19.15-19.15")
    )
)
// ── 自锁式超车按键（Boost）───
// 用户提供 SVG：viewBox="5 25.75 90 48.49"，3 条同形 path 错位排列成三连箭头，
// 全部 fill。
// ⚠️ **路径坐标已平移**：viewBox 的四元组是 `minX minY width height`，原图图形
// 占 x:5→95 / y:25.75→74.24。但 Compose 的 ImageVector **不支持非零 viewport
// 原点**（viewportWidth/Height 只定义 0..W × 0..H）——若照搬原坐标配 90×48.49 的
// viewport，图形只有 y<48.49 的部分可见，即**只画出上面约一半**（(48.49−26.8)/
// (71−26.8)≈49%，正是实机看到的"只显示一半"）。故三个 path 的起始点各减去
// (5, 25.75)：m36.902/65.797/94.691 48.891 → 31.902/60.797/89.691 23.141。
// 后续命令全是相对坐标（c/l 小写），不受平移影响，字节原样保留。
// ⚠️ 图形比例 ≈1.856:1（扁长），intrinsic 尺寸必须同比例 —— 沿用 24×24 会纵向
// 拉伸 1.86 倍（箭头变瘦高），故给 24 × 12.93dp。
val BoostIcon: ImageVector = svgIconMulti(
    "BoostIcon", 90f, 48.49f,
    listOf(
        SvgPath(d = "m31.902 23.141-13.344-22.102c-0.38672-0.64453-1.0859-1.0352-1.8359-1.0352h-14.574c-1.6719 0-2.6992 1.8242-1.8359 3.2539l11.996 19.883c0.41016 0.67969 0.41016 1.5352 0 2.2148l-11.996 19.883c-0.86328 1.4297 0.16797 3.2539 1.8359 3.2539h14.582c0.75 0 1.4492-0.39453 1.8359-1.0352l13.332-22.102c0.41016-0.68359 0.41016-1.5352 0-2.2188z"),
        SvgPath(d = "m60.797 23.141-13.344-22.102c-0.38672-0.64453-1.0859-1.0352-1.8359-1.0352h-14.57c-1.668 0-2.6992 1.8242-1.8359 3.2539l11.996 19.883c0.41016 0.67969 0.41016 1.5352 0 2.2148l-11.996 19.883c-0.86328 1.4297 0.16797 3.2539 1.8359 3.2539h14.582c0.75 0 1.4492-0.39453 1.8359-1.0352l13.332-22.102c0.41016-0.68359 0.41016-1.5352 0-2.2188z"),
        SvgPath(d = "m89.691 23.141-13.344-22.102c-0.38672-0.64453-1.0859-1.0352-1.8359-1.0352h-14.57c-1.6719 0-2.6992 1.8242-1.8359 3.2539l11.996 19.883c0.41016 0.67969 0.41016 1.5352 0 2.2148l-11.996 19.883c-0.86328 1.4297 0.16797 3.2539 1.8359 3.2539h14.582c0.75 0 1.4492-0.39453 1.8359-1.0352l13.332-22.102c0.41016-0.68359 0.41016-1.5352 0-2.2188z"),
    ),
    defaultWidth = 24.dp,
    defaultHeight = 12.93.dp
)

// ── 禁止删除下一圈成绩（禁止符）───
// 用户提供 SVG：`viewBox="2.5 2.5 95 95"`，单条 path 三个子路径（外圆 + 斜杠的
// 两个半月牙），默认 nonzero 填充即得"圆环 + 对角斜杠"。已实测 `compare -metric AE`
// 确认 nonzero 与 evenodd 渲染结果逐像素相同（子路径绕向已保证 nonzero 不误填）。
// viewBox 原点非零 → 必须传 viewportOriginX/Y=2.5（由 svgIconMulti 的 group 平移）。
// 验证法：rsvg-convert 渲染原图与"包一层 `<g transform="translate(-2.5,-2.5)>`"
// 的参考图，compare -metric AE = 0（实测通过）。图形为正方形 → 默认 24×24dp。
val ForbidIcon: ImageVector = svgIconMulti(
    "ForbidIcon", 95f, 95f,
    listOf(
        SvgPath(
            d = "M50,2.5C23.8,2.5,2.5,23.8,2.5,50c0,26.2,21.3,47.5,47.5,47.5S97.5,76.2,97.5,50C97.5,23.8,76.2,2.5,50,2.5z M85.1,50 c0,7.4-2.3,14.3-6.3,20L30,21.2c5.7-4,12.6-6.3,20-6.3C69.4,14.9,85.1,30.6,85.1,50z M14.9,50c0-7.4,2.3-14.3,6.3-20L70,78.8 c-5.7,4-12.6,6.3-20,6.3C30.6,85.1,14.9,69.4,14.9,50z"
        )
    ),
    viewportOriginX = 2.5f,
    viewportOriginY = 2.5f
)

// ── 滑移率反馈（漂移轮胎）───
// 用户提供 SVG：`viewBox="5.28 12.57 79.37 64.82"`，4 条 path，d 数据逐字搬运
// （空行/多空格已在搬运时归一为单空格，其余一个字符都没动）。
// path 0/1/2 = 三道同心弧（漂移痕迹），path 3 = 轮胎 + 拖痕主体。
// ⚠️ viewBox 原点非零 → 必须传 viewportOriginX/Y，由 svgIconMulti 的
// group(translationX/Y) 平移（等价于 SVG 的 `<g transform="translate(...)">`）。
// 验证法：rsvg-convert 渲染原图与"包一层同名 <g>"的参考图，compare -metric AE = 0。
// ⚠️ 图形比例 ≈1.224:1（扁），intrinsic 尺寸按同比例给 24 × 19.6dp。
val DriftIcon: ImageVector = svgIconMulti(
    "DriftIcon", 79.37f, 64.82f,
    listOf(
        SvgPath(
            d = "M65.948,32.912l-2.191,3.074C74.61,45.97,80.901,60.021,80.901,74.882h3.746 C84.647,58.814,77.772,43.621,65.948," +
                "32.912z"
        ),
        SvgPath(
            d = "M52.354,51.517l-2.219,3.021c5.953,4.896,9.5,12.358,9.5,20.344h3.75 C63.386,65.719,59.276,57.131,52.354,51.517z"
        ),
        SvgPath(
            d = "M59.335,42.006l-2.225,3.032c8.443,7.41,13.385,18.295,13.385,29.844h3.746 C74.241,62.152,68.757,50.142,59.335,4" +
                "2.006z"
        ),
        SvgPath(
            d = "M40.354,17.423c-0.469-0.318-0.723-0.73-0.703-1.147 c0.047-0.853,1.203-1.489,2.584-1.416c1.375,0.073,2.459,0.81" +
                "7,2.416,1.672c-0.041,0.853-1.197,1.484-2.578,1.416 C41.423,17.912,40.808,17.724,40.354,17.423z M58.538,30.772c" +
                "-0.443-0.339-0.828-0.887-1.063-1.521 c-0.484-1.297-0.225-2.589,0.578-2.885c0.797-0.298,1.838,0.516,2.316,1.807" +
                "c0.484,1.297,0.225,2.589-0.578,2.885 c-0.338,0.125-0.744,0.052-1.141-0.203C58.616,30.828,58.573,30.798,58.538," +
                "30.772z M48.62,43.517 c-2.145-2.328-4.984-4.88-8.145-7.25c-3.209-2.308-6.49-4.246-9.355-5.594l3.834-7.094c0.06" +
                "3-0.109,0.109-0.511,1.615-0.36 c1.51,0.147,3.691,0.163,9.979,4.776V28l0.006-0.005c6.281,4.614,6.947,6.693,7.54" +
                "1,8.084c0.594,1.396,0.229,1.563,0.141,1.651 L48.62,43.517z M22.813,60.328c-2.781-2.041-7.682-6.342-7.734-10.07" +
                "1l6.078-6.151c1.635,1.859,6.025,5.276,8.438,6.984 c2.354,1.792,6.928,4.958,9.193,5.958l-4.047,7.64C31.167,65.7" +
                "57,25.595,62.371,22.813,60.328z M9.132,63.443 c-1.162-2.333-1.24-4.176-0.418-5.295l1.975-2.688c0.24,0.443,0.44" +
                "3,0.865,0.584,1.251c0.76,2.114,0.254,3.473-0.324,4.26 L9.132,63.443z M23.944,74.314l1.816-2.475c0.578-0.787,1." +
                "719-1.678,3.965-1.583c0.41,0.016,0.875,0.083,1.369,0.182l-1.975,2.687 C28.298,74.246,26.517,74.719,23.944,74.3" +
                "14z M13.604,72.876c6.891,5.057,14.74,6.005,17.594,2.119l6.24-8.504l2.256,0.249 c1.932,0.214,3.328-2.692,0.473-" +
                "3.448l-0.316-0.083l21.801-29.704c1.834-2.5,3.016-8.328-7.416-15.989 C43.804,9.86,38.595,12.74,36.761,15.24L14." +
                "96,44.939l-0.178-0.277c-1.572-2.5-3.928-0.295-3.146,1.486l0.918,2.077L6.308,56.73 C3.46,60.615,6.71,67.814,13." +
                "604,72.876z"
        ),
    ),
    defaultWidth = 24.dp,
    defaultHeight = 19.6.dp,
    viewportOriginX = 5.28f,
    viewportOriginY = 12.57f
)

// ── 振动强度（手机 + 两侧波纹）───
// 用户提供 SVG：`viewBox="2 2 28 28"`，单 path（内含 `matrix(1,0,0,1,-192,-48)`
// 平移），用于「抓地力反馈 → 最大振动强度」滑条（2026-10-05 用户换图标）。
//
// ⚠️ **d 数据已把全部变换烘焙进坐标**（`matrix` 平移 + viewBox 原点回零），
// 不再走 `viewportOriginX/Y`：烘焙后的坐标域 = 0..28，viewBox 设 `0 0 28 28`。
// 验证法：`rsvg-convert` 渲染原图与烘焙图，`compare -metric AE` = **0**（实测）。
// ⚠️ 烘焙脚本只处理 M/L/C/Z（本图恰好只用这些）；不要用通用脚本盲改含
// `a`（弧）/`h`/`v`/相对命令的 path——见 CustomIcons.kt 顶部关于"不要改 d 坐标"
// 的告警（曾有格式化脚本丢 `z` 导致图标走形）。
// 比例 = 1:1（28×28），intrinsic 用默认 24×24。
val VibrationIntensityIcon: ImageVector = svgIconMulti(
    "VibrationIntensityIcon", 28f, 28f,
    listOf(
        SvgPath(
            d = "M 22 3 C 22 1.343 20.657 0 19 0 L 9 0 C 7.343 0 6 1.343 6 3 L 6 25 C 6 26.657 7.343 28 9 28 L 19 28 " +
                "C 20.657 28 22 26.657 22 25 L 22 3 Z M 20 3 L 20 25 C 20 25.552 19.552 26 19 26 C 19 26 9 26 9 26 " +
                "C 8.448 26 8 25.552 8 25 C 8 25 8 3 8 3 C 8 2.448 8.448 2 9 2 C 9 2 19 2 19 2 C 19.552 2 20 2.448 20 3 Z " +
                "M 4.006 5 L 4 5 C 3.448 5 3 5.448 3 6 L 3 22 C 3 22.552 3.448 23 4 23 L 4.006 23 C 4.555 22.997 5 22.55 5 22 " +
                "L 5 6 C 5 5.45 4.555 5.003 4.006 5 Z M 23 6 L 23 22 C 23 22.552 23.448 23 24 23 C 24.552 23 25 22.552 25 22 " +
                "L 25 6 C 25 5.448 24.552 5 24 5 C 23.448 5 23 5.448 23 6 Z M 1.006 9 L 1 9 C 0.448 9 0 9.448 0 10 L 0 18 " +
                "C 0 18.552 0.448 19 1 19 L 1.006 19 C 1.555 18.997 2 18.55 2 18 L 2 10 C 2 9.45 1.555 9.003 1.006 9 Z " +
                "M 26 10 L 26 18 C 26 18.552 26.448 19 27 19 C 27.552 19 28 18.552 28 18 L 28 10 C 28 9.448 27.552 9 27 9 " +
                "C 26.448 9 26 9.448 26 10 Z",
            pathFillType = PathFillType.EvenOdd
        ),
    )
)

// ── 路肩（并排两块路肩石）───
// 用户提供 SVG：`viewBox="0 0 32 16"`，单 path（fill-rule:nonzero，两个子路径：
// 左侧 `M8,1 L8,15 L1,15 L1,1 Z` + 外框 `M16,0 L0,0 L0,16 L16,16 Z`；右侧同构）。
// 用于「路肩振感反馈」开关（2026-10-05 用户换图标）。
// 比例 = 2:1（扁长），intrinsic 按同比例给 24 × 12dp（否则非等比拉伸）。
// ⚠️ 与 DriftIcon/BoostIcon 同坑：非正方 viewport 必须同比例传 defaultWidth/Height。
val KerbIcon: ImageVector = svgIconMulti(
    "KerbIcon", 32f, 16f,
    listOf(
        SvgPath(
            d = "M8,1 L8,15 L1,15 L1,1 Z M16,0 L0,0 L0,16 L16,16 Z M24,1 L24,15 L17,15 L17,1 Z M32,0 L16,0 L16,16 L32,16 Z",
            pathFillType = PathFillType.NonZero
        ),
    ),
    defaultWidth = 24.dp,
    defaultHeight = 12.dp
)

// ── 围场（Paddock）底栏 icon：挥舞方格旗 ───
// 用户提供 SVG（100×100 viewBox，单 path fill），path 数据原样搬运。
val ChequeredFlagIcon: ImageVector = svgIconMulti(
    "ChequeredFlagIcon", 100f, 100f,
    listOf(
        SvgPath(
            d = "M79.8378143,42.9190979c-1.7933884,-6.4818802 -2.5734024,-10.7505035 -2.5734024,-10.7505035c-5.2981339,0.1734619 -9.0009995,-0.2394924 -12.3281174,-1.1448841c0.5646439,2.5786457 1.5362473,6.9100704 2.795105,12.1236591C72.9734879,44.2131424 76.8772049,44.042244 79.8378143,42.9190979zM50.7572327,37.3709259c1.4860646,0.6488419 2.9074364,1.240345 4.2760925,1.7850304c-1.7170296,-6.8741379 -2.8604622,-11.7523994 -3.3135109,-13.7112617c-2.555088,-1.1844597 -5.5155106,-2.4999065 -9.1376724,-3.9286156c-2.4020996,-0.9474926 -4.7249413,-1.7392864 -6.9713821,-2.3968239l3.5212708,14.531002C42.645462,34.3648387 46.550293,35.5340195 50.7572327,37.3709259zM43.5824394,52.0162659l4.9174652,20.2918777c3.0720139,0.738472 6.3096848,1.8565216 9.689415,3.4608154c2.6797218,1.2723007 5.2113991,2.3045028 7.5955544,3.1349945c-2.3365936,-7.9888603 -4.4484024,-15.6120796 -6.2882462,-22.4916801c-0.1896248,-0.0725746 -0.3728561,-0.1389771 -0.5644875,-0.2137413C53.1844177,53.9522781 48.0428429,52.6814575 43.5824394,52.0162659zM85.9003906,60.9153366c-2.837944,0.4322014 -7.352829,-0.5766029 -13.7532196,-0.7210426c2.1678848,7.7537727 4.6110153,15.5554543 7.0873566,21.6345253c10.434759,0.7558594 15.6185913,-2.8392487 15.6185913,-2.8392487C91.2565613,73.1103246 88.2977676,66.8652039 85.9003906,60.9153366zM5.4330058,18.1583099c7.5793872,31.6164207 11.1373836,59.8402176 11.1373836,59.8402176s5.2919598,-4.2918472 14.208456,-6.0972061c-1.2835255,-6.6875992 -2.6189651,-13.3512611 -3.91572,-19.653141c3.0870628,-0.6764145 8.8244534,-1.409462 16.7192135,-0.2319145l-4.4504089,-18.3660088c-7.6423721,-1.5622253 -13.4095631,-0.9881796 -16.2934017,-0.4662781c-1.6324639,-7.5505886 -2.9589722,-13.4830303 -3.6838436,-16.6949482C19.1547852,16.4890308 12.5195866,15.1160297 5.4330058,18.1583099zM55.0333252,39.1559563c1.2145615,4.8626938 2.7159882,10.7220309 4.4633026,17.2563171c4.8414645,1.8587189 9.0447388,3.0507965 12.6505432,3.7820206c-1.673439,-5.9858017 -3.1826706,-11.939312 -4.4157715,-17.0469246C64.1624146,42.5696958 59.9718246,41.1210308 55.0333252,39.1559563z"
        ),
    )
)
