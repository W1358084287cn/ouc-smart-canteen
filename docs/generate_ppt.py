# -*- coding: utf-8 -*-
"""
OUC智慧食堂 —— PPT 演示生成脚本
使用 Playwright 对真实运行项目截图，python-pptx 生成 PPT
"""

import os
import sys
import time
from playwright.sync_api import sync_playwright

# ===================== 配置 =====================
BASE_URL = "http://localhost:8080"
SCREENSHOT_DIR = os.path.join(os.path.dirname(__file__), "screenshots")
PPT_OUTPUT = os.path.join(os.path.dirname(__file__), "OUC智慧食堂-项目演示.pptx")

os.makedirs(SCREENSHOT_DIR, exist_ok=True)

# ===================== 截图任务定义 =====================
# (截图文件名, 页面URL, 是否需要登录, 描述)
SCREENSHOTS_BACKEND = [
    ("01-后台登录页", "/backend/page/login/login.html", False, "后台管理系统登录页"),
    ("02-后台首页", "/backend/index.html", True, "后台管理首页仪表盘"),
    ("03-菜品管理", "/backend/page/food/list.html", True, "菜品管理 - 分页浏览/搜索/启售停售"),
    ("04-套餐管理", "/backend/page/combo/list.html", True, "套餐管理 - 套餐CRUD/批量操作"),
    ("05-订单管理", "/backend/page/order/list.html", True, "订单管理 - 实时接单/状态流转/退款审核"),
    ("06-桌台管理", "/backend/page/table/list.html", True, "桌台管理 - A/B/C三区18张桌台/二维码"),
    ("07-收银台", "/backend/page/cashier/index.html", True, "收银台 - 今日统计/历史分页/配置管理"),
    ("08-分类管理", "/backend/page/category/list.html", True, "菜品/套餐分类管理"),
]

SCREENSHOTS_FRONTEND = [
    ("09-C端登录页", "/front/page/login.html", False, "移动端邮箱验证码登录"),
    ("10-C端点餐首页", "/front/index.html", True, "移动端点餐首页 - 分类浏览/搜索"),
    ("11-C端订单列表", "/front/page/order.html", True, "移动端我的订单 - 状态追踪/退款申请"),
]


def take_screenshots():
    """使用 Playwright 截取所有页面截图"""
    results = []

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 1920, "height": 1080},
            device_scale_factor=1,
        )
        page = context.new_page()

        # ========== 1. 后台登录 ==========
        print("[*] 正在进行后台登录...")
        page.goto(f"{BASE_URL}/backend/page/login/login.html", wait_until="networkidle")
        page.wait_for_timeout(3000)  # 等待 Vue + Element UI 渲染

        # 截图：后台登录页
        print("  [拍照] 后台登录页")
        page.screenshot(path=os.path.join(SCREENSHOT_DIR, "01-后台登录页.png"), full_page=False)
        results.append(("01-后台登录页", "后台管理系统登录页"))

        # 执行登录（Element UI 渲染的 input 用 placeholder 定位）
        try:
            page.fill("input[placeholder='账号']", "admin")
            page.fill("input[placeholder='密码']", "123456")
            page.click("button:has-text('登录')")
            page.wait_for_timeout(4000)  # 等待登录跳转和首页渲染
            current_url = page.url
            if "login" not in current_url:
                print("  [OK] 后台登录成功，当前页面:", current_url)
            else:
                print("  [WARN] 后台登录可能失败，URL:", current_url)
        except Exception as e:
            print(f"  [WARN] 后台登录异常: {e}")

        # ========== 2. 后台各页面截图 ==========
        backend_pages = [
            ("02-后台首页", "/backend/index.html", "后台管理首页仪表盘 - 侧边栏导航 + 数据概览"),
            ("03-菜品管理", "/backend/page/food/list.html", "菜品管理 - 分页浏览/搜索/启售停售（50道菜品）"),
            ("04-套餐管理", "/backend/page/combo/list.html", "套餐管理 - 套餐CRUD/批量操作"),
            ("05-订单管理", "/backend/page/order/list.html", "订单管理 - 实时接单/状态流转/退款审核（9笔订单）"),
            ("06-桌台管理", "/backend/page/table/list.html", "桌台管理 - A/B/C三区18张桌台/二维码生成"),
            ("07-收银台", "/backend/page/cashier/index.html", "收银台 - 今日统计/历史分页/配置管理"),
            ("08-分类管理", "/backend/page/category/list.html", "菜品/套餐分类管理（9个分类）"),
        ]

        for name, url, desc in backend_pages:
            print(f"  [拍照] {name}")
            try:
                page.goto(f"{BASE_URL}{url}", wait_until="networkidle", timeout=20000)
                page.wait_for_timeout(3500)  # 等待 Vue 数据加载和渲染
                page.screenshot(path=os.path.join(SCREENSHOT_DIR, f"{name}.png"), full_page=False)
                results.append((name, desc))
            except Exception as e:
                print(f"  [WARN] {name} 截图失败: {e}")
                results.append((name, f"{desc} (截图失败)"))

        # ========== 3. C端页面（移动端视口） ==========
        # 切换到移动端视口
        page.set_viewport_size({"width": 414, "height": 896})

        front_pages = [
            ("09-C端登录页", "/front/page/login.html", "移动端邮箱验证码登录页"),
            ("10-C端点餐首页", "/front/index.html", "移动端点餐首页 - 分类浏览/菜品列表/搜索"),
            ("11-C端订单列表", "/front/page/order.html", "移动端我的订单 - 状态追踪/退款申请"),
        ]

        for name, url, desc in front_pages:
            print(f"  [拍照] {name}")
            try:
                page.goto(f"{BASE_URL}{url}", wait_until="networkidle", timeout=20000)
                page.wait_for_timeout(3500)  # 等待 Vue/Vant UI 渲染
                page.screenshot(path=os.path.join(SCREENSHOT_DIR, f"{name}.png"), full_page=False)
                results.append((name, desc))
            except Exception as e:
                print(f"  [WARN] {name} 截图失败: {e}")

        # ========== 4. 关闭 ==========
        browser.close()

    print(f"\n[完成] 共截图 {len(results)} 张，保存到 {SCREENSHOT_DIR}")
    return results


# ===================== PPT 生成 =====================
def create_ppt(screenshot_results):
    """使用 python-pptx 生成项目演示 PPT"""
    from pptx import Presentation
    from pptx.util import Inches, Pt, Emu
    from pptx.dml.color import RGBColor
    from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
    from pptx.enum.shapes import MSO_SHAPE

    prs = Presentation()
    prs.slide_width = Inches(13.333)  # 16:9 宽屏
    prs.slide_height = Inches(7.5)

    # 颜色方案
    PRIMARY = RGBColor(0x1A, 0x56, 0xDB)       # 品牌蓝
    DARK_BG = RGBColor(0x0F, 0x1A, 0x2F)        # 深色背景
    ACCENT = RGBColor(0x00, 0xB4, 0xD8)          # 青色点缀
    WHITE = RGBColor(0xFF, 0xFF, 0xFF)
    LIGHT_GRAY = RGBColor(0xE8, 0xEC, 0xF1)
    DARK_TEXT = RGBColor(0x1A, 0x1A, 0x2E)
    GREEN = RGBColor(0x10, 0xB9, 0x81)
    ORANGE = RGBColor(0xF5, 0x9E, 0x0B)

    def add_bg(slide, color=DARK_BG):
        """添加纯色背景"""
        bg = slide.background
        fill = bg.fill
        fill.solid()
        fill.fore_color.rgb = color

    def add_shape_bg(slide, left, top, width, height, color, alpha=None):
        """添加矩形色块"""
        shape = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, width, height)
        shape.fill.solid()
        shape.fill.fore_color.rgb = color
        shape.line.fill.background()
        if alpha is not None:
            from pptx.oxml.ns import qn
            shape.fill.fore_color._color.set(qn('a:alpha'), str(int(alpha * 1000)))
        return shape

    def add_text_box(slide, left, top, width, height, text, font_size=18, color=WHITE, bold=False, alignment=PP_ALIGN.LEFT, font_name="微软雅黑"):
        """添加文本框"""
        txBox = slide.shapes.add_textbox(left, top, width, height)
        tf = txBox.text_frame
        tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = text
        p.font.size = Pt(font_size)
        p.font.color.rgb = color
        p.font.bold = bold
        p.font.name = font_name
        p.alignment = alignment
        return txBox

    def add_decorated_title(slide, title, subtitle=None):
        """添加带装饰的标题区"""
        # 顶部蓝色装饰条
        add_shape_bg(slide, Inches(0), Inches(0), prs.slide_width, Inches(0.06), PRIMARY)
        # 标题
        add_text_box(slide, Inches(0.8), Inches(0.3), Inches(11), Inches(0.7),
                     title, font_size=32, color=WHITE, bold=True)
        if subtitle:
            add_text_box(slide, Inches(0.8), Inches(0.9), Inches(11), Inches(0.4),
                         subtitle, font_size=14, color=ACCENT)

    # ==========================================
    # Slide 1: 封面
    # ==========================================
    slide = prs.slides.add_slide(prs.slide_layouts[6])  # 空白布局
    add_bg(slide)

    # 大号装饰圆
    circle = slide.shapes.add_shape(MSO_SHAPE.OVAL, Inches(8.5), Inches(-1.5), Inches(6.5), Inches(6.5))
    circle.fill.solid()
    circle.fill.fore_color.rgb = PRIMARY
    circle.line.fill.background()

    circle2 = slide.shapes.add_shape(MSO_SHAPE.OVAL, Inches(9.5), Inches(3.5), Inches(3.5), Inches(3.5))
    circle2.fill.solid()
    circle2.fill.fore_color.rgb = ACCENT
    circle2.line.fill.background()

    # 标题
    add_text_box(slide, Inches(0.8), Inches(1.5), Inches(8), Inches(1.2),
                 "OUC 智慧食堂", font_size=56, color=WHITE, bold=True)
    add_text_box(slide, Inches(0.8), Inches(2.7), Inches(8), Inches(0.8),
                 "校园堂食扫码点餐系统", font_size=28, color=ACCENT)

    # 分隔线
    add_shape_bg(slide, Inches(0.8), Inches(3.7), Inches(4), Inches(0.04), PRIMARY)

    # 副标题信息
    add_text_box(slide, Inches(0.8), Inches(4.1), Inches(6), Inches(0.5),
                 "Vibe Coding · 提示词工程驱动 · 全栈项目", font_size=18, color=LIGHT_GRAY)
    add_text_box(slide, Inches(0.8), Inches(4.7), Inches(6), Inches(0.5),
                 "Spring Boot + MyBatis-Plus + MySQL + Redis + Netty + Vue.js", font_size=14, color=RGBColor(0x90, 0xA4, 0xAE))

    # 底部项目信息
    add_text_box(slide, Inches(0.8), Inches(6.2), Inches(6), Inches(0.4),
                 "14 个 Controller · 12 个 Service · 4 个独立子系统 · 14000+ 行代码", font_size=13, color=RGBColor(0x78, 0x90, 0x9C))

    # ==========================================
    # Slide 2: 项目概述
    # ==========================================
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_bg(slide)
    add_decorated_title(slide, "项目概述", "PROJECT OVERVIEW")

    # 左侧信息卡片
    cards_left = [
        ("🏫", "项目定位", "面向高校食堂的堂食扫码点餐系统\n学生扫码 → 同桌拼单 → 后厨调度 → 叫号取餐\n覆盖 C端点餐 + B端管理 + 后厨调度 全流程"),
        ("⚡", "开发范式", "Vibe Coding · 提示词工程驱动\n通过 30+ 锚点规则体系精准指挥 AI 编码\n0 手写业务代码，100% 由 AI 通过自然语言指令生成"),
    ]

    for i, (icon, title, desc) in enumerate(cards_left):
        y = Inches(1.6) + Inches(i * 2.8)
        card = add_shape_bg(slide, Inches(0.6), y, Inches(5.8), Inches(2.5), RGBColor(0x16, 0x24, 0x3E))
        add_text_box(slide, Inches(0.9), y + Inches(0.15), Inches(5.2), Inches(0.5),
                     f"{icon}  {title}", font_size=20, color=ACCENT, bold=True)
        add_text_box(slide, Inches(0.9), y + Inches(0.7), Inches(5.2), Inches(1.6),
                     desc, font_size=13, color=LIGHT_GRAY)

    # 右侧数据卡片
    cards_right = [
        ("14000+", "总代码行数\nJava 8000+ / 前端 6000+"),
        ("14 / 12", "Controller / Service\n三层架构标准化"),
        ("4", "独立子系统\n退款 · 收银台 · 后厨 · 聊天"),
        ("20", "前后端页面\nC端8页 + B端12页"),
        ("3-4周", "开发周期\n业余碎片化时间"),
        ("18张", "桌台管理\nA/B/C三区全覆盖"),
    ]

    for i, (num, desc) in enumerate(cards_right):
        col = i % 3
        row = i // 3
        x = Inches(7.2) + Inches(col * 2.0)
        y = Inches(1.6) + Inches(row * 2.8)
        card = add_shape_bg(slide, x, y, Inches(1.8), Inches(2.5), RGBColor(0x16, 0x24, 0x3E))
        add_text_box(slide, x + Inches(0.1), y + Inches(0.3), Inches(1.6), Inches(0.7),
                     num, font_size=28, color=GREEN, bold=True, alignment=PP_ALIGN.CENTER)
        add_text_box(slide, x + Inches(0.1), y + Inches(1.2), Inches(1.6), Inches(1.1),
                     desc, font_size=11, color=LIGHT_GRAY, alignment=PP_ALIGN.CENTER)

    # ==========================================
    # Slide 3: 技术架构
    # ==========================================
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_bg(slide)
    add_decorated_title(slide, "技术架构与指挥体系", "TECH STACK & PROMPT ENGINEERING SYSTEM")

    # 左侧：技术栈
    add_text_box(slide, Inches(0.6), Inches(1.5), Inches(3), Inches(0.5),
                 "技术栈总览", font_size=18, color=ACCENT, bold=True)

    tech_items = [
        ("构建工具", "Maven"),
        ("后端框架", "Spring Boot 2.7 + MyBatis-Plus 3.5"),
        ("数据库", "MySQL 8.0 + Redis 缓存"),
        ("即时通讯", "Netty WebSocket 自建服务"),
        ("后台前端", "Vue.js + Element UI"),
        ("移动端", "Vant UI 移动端组件库"),
        ("容器化", "Docker + Docker Compose"),
        ("工具库", "Lombok / Jackson / ZXing / JavaMail"),
    ]

    for i, (label, tech) in enumerate(tech_items):
        y = Inches(2.1) + Inches(i * 0.6)
        add_text_box(slide, Inches(0.8), y, Inches(1.8), Inches(0.5),
                     f"▸ {label}", font_size=12, color=ACCENT, bold=True)
        add_text_box(slide, Inches(2.7), y, Inches(3.5), Inches(0.5),
                     tech, font_size=12, color=LIGHT_GRAY)

    # 右侧：指挥体系
    add_text_box(slide, Inches(6.5), Inches(1.5), Inches(6), Inches(0.5),
                 "\U0001f3af 提示词工程指挥体系", font_size=18, color=GREEN, bold=True)

    command_items = [
        "守门总规则 → 约束AI行为边界，无锚点拒绝执行",
        "30+锚点索引 → 精确的代码模板与约束规范",
        "分页查询三板斧 → 标准化CRUD范式",
        "DTO封装模式 → 多表联查统一规范",
        "多表操作铁律 → @Transactional强制约束",
        "Stream流惯用范式 → 统一函数式编程风格",
        "三层架构标准 → Controller薄层/Service接口+Impl",
    ]

    for i, item in enumerate(command_items):
        y = Inches(2.1) + Inches(i * 0.6)
        card = add_shape_bg(slide, Inches(6.6), y, Inches(6.0), Inches(0.5), RGBColor(0x16, 0x24, 0x3E))
        add_text_box(slide, Inches(6.8), y + Inches(0.05), Inches(5.6), Inches(0.4),
                     f"✓ {item}", font_size=12, color=LIGHT_GRAY)

    # ==========================================
    # Slides 4~11: 截图展示页
    # ==========================================
    for name, desc in screenshot_results:
        img_path = os.path.join(SCREENSHOT_DIR, f"{name}.png")
        if not os.path.exists(img_path):
            continue

        slide = prs.slides.add_slide(prs.slide_layouts[6])
        add_bg(slide)
        add_decorated_title(slide, name.split("-", 1)[1] if "-" in name else name, desc)

        # 添加截图
        try:
            from PIL import Image
            img = Image.open(img_path)
            img_w, img_h = img.size
        except:
            img_w, img_h = 1440, 900

        # 计算合适尺寸（保持比例，适配幻灯片）
        max_w = Inches(11.5)
        max_h = Inches(5.8)
        ratio = min(max_w / img_w, max_h / img_h)
        pic_w = int(img_w * ratio)
        pic_h = int(img_h * ratio)

        # 居中放置
        left = (prs.slide_width - pic_w) // 2
        top = Inches(1.5) + (max_h - pic_h) // 2

        slide.shapes.add_picture(img_path, left, top, pic_w, pic_h)

        # 底部说明
        add_text_box(slide, Inches(0.8), Inches(7.0), Inches(11), Inches(0.4),
                     f"📍 {desc}", font_size=12, color=RGBColor(0x78, 0x90, 0x9C))

    # ==========================================
    # Slide 倒数第二: 核心亮点
    # ==========================================
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_bg(slide)
    add_decorated_title(slide, "核心功能亮点", "KEY FEATURES")

    features = [
        ("🔐", "双端认证体系", "后台MD5登录 + C端邮箱验证码\n30天Cookie签名Token记住我\nFilter + AntPathMatcher白名单拦截"),
        ("🛒", "同桌共享购物车", "Redis List按桌号存储\n多人扫码拼单一起下单\nZXing二维码自动生成"),
        ("🍳", "后厨调度子系统", "4槽位并行加工模型\n优先级队列+Semaphore并发控制\n订单状态实时可视追踪"),
        ("💰", "退款全链路", "申请→审核→处理完整闭环\n支持同意/拒绝/部分退款\n语音通知+红点未读提示"),
        ("📊", "收银台统计", "日营业额/退款/净收入汇总\n纯内存ConcurrentSkipListMap\n跨天自动刷新+定时清理"),
        ("💬", "实时客服聊天", "Netty WebSocket自建服务\n多窗口客服面板\n输入状态感知+未读红点"),
    ]

    for i, (icon, title, desc) in enumerate(features):
        col = i % 3
        row = i // 3
        x = Inches(0.6) + Inches(col * 4.1)
        y = Inches(1.6) + Inches(row * 2.9)
        card = add_shape_bg(slide, x, y, Inches(3.8), Inches(2.6), RGBColor(0x16, 0x24, 0x3E))
        add_text_box(slide, x + Inches(0.2), y + Inches(0.15), Inches(3.4), Inches(0.5),
                     f"{icon}  {title}", font_size=18, color=ACCENT, bold=True)
        add_text_box(slide, x + Inches(0.2), y + Inches(0.8), Inches(3.4), Inches(1.6),
                     desc, font_size=12, color=LIGHT_GRAY)

    # ==========================================
    # Slide 最后: 总结
    # ==========================================
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    add_bg(slide)

    # 装饰
    add_shape_bg(slide, Inches(0), Inches(0), prs.slide_width, Inches(0.06), PRIMARY)

    add_text_box(slide, Inches(0.8), Inches(0.8), Inches(11), Inches(0.8),
                 "项目总结", font_size=36, color=WHITE, bold=True)

    add_shape_bg(slide, Inches(0.8), Inches(1.6), Inches(4), Inches(0.03), PRIMARY)

    summary_points = [
        "✅  项目从零到可交付使用，周期约 3-4 周（业余碎片时间）",
        "✅  100% Vibe Coding 驱动 —— 0 手写业务代码，全部由 AI 通过自然语言指令生成",
        "✅  建立 30+ 锚点规则体系，确保 AI 产出代码风格统一、架构一致",
        "✅  Java 8000+ 行 + 前端 6000+ 行，14 个 Controller，4 个独立子系统",
        "✅  覆盖 扫码点餐→下单→后厨→退款→收银→客服 全链路业务闭环",
        "✅  Docker 一键部署，含完整建库 SQL 和环境搭建文档",
        "✅  主动识别并修复 5+ 高危 CVE 安全漏洞",
    ]

    for i, point in enumerate(summary_points):
        y = Inches(1.9) + Inches(i * 0.65)
        add_text_box(slide, Inches(1.0), y, Inches(11), Inches(0.6),
                     point, font_size=16, color=LIGHT_GRAY)

    # 底部联系信息区
    add_shape_bg(slide, Inches(0), Inches(6.5), prs.slide_width, Inches(1.0), RGBColor(0x12, 0x1E, 0x36))
    add_text_box(slide, Inches(0.8), Inches(6.6), Inches(11), Inches(0.4),
                 "技术栈: Maven · Spring Boot · MyBatis-Plus · MySQL · Redis · Netty · Vue.js · Docker",
                 font_size=12, color=ACCENT)
    add_text_box(slide, Inches(0.8), Inches(6.95), Inches(11), Inches(0.4),
                 "Vibe Coding 驱动 · 提示词工程 · 全栈项目实战",
                 font_size=12, color=RGBColor(0x78, 0x90, 0x9C))

    # 保存
    prs.save(PPT_OUTPUT)
    print(f"\n[完成] PPT 已生成: {PPT_OUTPUT}")
    print(f"       共 {len(prs.slides)} 页幻灯片")


# ===================== 主流程 =====================
if __name__ == "__main__":
    print("=" * 60)
    print("  OUC 智慧食堂 - PPT 演示生成工具")
    print("=" * 60)

    # Step 1: 截图
    print("\n[Step 1/2] 正在截取项目页面...")
    results = take_screenshots()

    # Step 2: 生成 PPT
    print(f"\n[Step 2/2] 正在生成 PPT...")
    create_ppt(results)

    print("\n" + "=" * 60)
    print(f"  全部完成！")
    print(f"  截图目录: {SCREENSHOT_DIR}")
    print(f"  PPT 文件: {PPT_OUTPUT}")
    print("=" * 60)