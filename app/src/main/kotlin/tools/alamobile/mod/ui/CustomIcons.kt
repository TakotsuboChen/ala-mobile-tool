package tools.alamobile.mod.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
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
    paths: List<SvgPath>
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight
    ).apply {
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
    }.build()

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