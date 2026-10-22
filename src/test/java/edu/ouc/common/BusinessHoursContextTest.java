package edu.ouc.common;

import edu.ouc.entity.BusinessHours;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 营业时间上下文单元测试（纯内存逻辑，无需 Spring 容器）
 *
 * 规则优先级：手动打烊 > 休息日 > 营业时段
 */
class BusinessHoursContextTest {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("HH:mm");

    private BusinessHoursContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new BusinessHoursContext();
    }

    private BusinessHours hours(Integer open, String start, String end, String restDays) {
        BusinessHours h = new BusinessHours();
        h.setOpen(open);
        h.setStartTime(start);
        h.setEndTime(end);
        h.setRestDays(restDays);
        return h;
    }

    @Test
    @DisplayName("默认配置：07:00-21:00，无休息日，默认营业")
    void defaults() {
        assertEquals("07:00", ctx.getHours().getStartTime());
        assertEquals("21:00", ctx.getHours().getEndTime());
        assertEquals(Integer.valueOf(1), ctx.getHours().getOpen());
        assertEquals("", ctx.getHours().getRestDays());
    }

    @Test
    @DisplayName("手动打烊：即使时间在营业时段内也必须返回 false")
    void manualClosedAlwaysWins() {
        ctx.update(hours(0, "00:00", "23:59", ""));
        assertFalse(ctx.isOpenNow(), "手动打烊优先级最高");
    }

    @Test
    @DisplayName("全时段营业：00:00-23:59 任何时刻都营业")
    void wholeDayOpen() {
        ctx.update(hours(1, "00:00", "23:59", ""));
        assertTrue(ctx.isOpenNow());
    }

    @Test
    @DisplayName("当前时刻落在营业时段内 → 营业中")
    void insideWindow() {
        LocalTime now = LocalTime.now();
        String start = now.minusMinutes(5).format(F);
        String end = now.plusMinutes(5).format(F);
        ctx.update(hours(1, start, end, ""));
        assertTrue(ctx.isOpenNow(), "窗口 " + start + "-" + end + " 应包含当前时刻");
    }

    @Test
    @DisplayName("当前时刻早于开始时间 → 打烊")
    void beforeWindow() {
        LocalTime now = LocalTime.now();
        // 避免午夜前后边界导致用例不稳定
        assumeTrue(now.isAfter(LocalTime.of(00, 10)));
        ctx.update(hours(1, now.plusMinutes(5).format(F), now.plusMinutes(10).format(F), ""));
        assertFalse(ctx.isOpenNow());
    }

    @Test
    @DisplayName("当前时刻晚于结束时间 → 打烊")
    void afterWindow() {
        LocalTime now = LocalTime.now();
        assumeTrue(now.isAfter(LocalTime.of(00, 10)));
        ctx.update(hours(1, "00:00", now.minusMinutes(2).format(F), ""));
        assertFalse(ctx.isOpenNow());
    }

    @Test
    @DisplayName("今天设为休息日 → 打烊（即使时段覆盖全天）")
    void restDay() {
        int today = LocalDateTime.now().getDayOfWeek().getValue(); // 1=Mon..7=Sun
        ctx.update(hours(1, "00:00", "23:59", String.valueOf(today)));
        assertFalse(ctx.isOpenNow(), "今天(星期" + today + ")是休息日");
    }

    @Test
    @DisplayName("休息日只影响当天：设为明天则不打烊")
    void otherRestDayDoesNotAffectToday() {
        int today = LocalDateTime.now().getDayOfWeek().getValue();
        int other = today == 7 ? 1 : today + 1;
        ctx.update(hours(1, "00:00", "23:59", String.valueOf(other)));
        assertTrue(ctx.isOpenNow());
    }

    @Test
    @DisplayName("非法时间格式 → 不抛异常，按打烊处理")
    void invalidTimeFormatIsSafe() {
        ctx.update(hours(1, "不是时间", "25:99", ""));
        assertDoesNotThrow(() -> ctx.isOpenNow());
        assertFalse(ctx.isOpenNow());
    }

    @Test
    @DisplayName("增量更新：只传 open 时保留原营业时段，只传时段时保留原开关")
    void partialUpdateKeepsOtherFields() {
        ctx.update(hours(1, "08:00", "22:00", ""));
        BusinessHours onlyToggle = new BusinessHours();
        onlyToggle.setOpen(0);
        ctx.update(onlyToggle);
        assertEquals("08:00", ctx.getHours().getStartTime(), "只改开关不应动营业时段");
        assertEquals("22:00", ctx.getHours().getEndTime());

        BusinessHours onlyTime = new BusinessHours();
        onlyTime.setStartTime("09:30");
        ctx.update(onlyTime);
        assertEquals("09:30", ctx.getHours().getStartTime());
        assertEquals("22:00", ctx.getHours().getEndTime(), "未传的结束时间不应被清空");
        assertEquals(Integer.valueOf(0), ctx.getHours().getOpen(), "未传的开关不应被重置");
    }

    @Test
    @DisplayName("空白字符串不覆盖已有配置（防止前端传空串把配置清空）")
    void blankValuesAreIgnored() {
        ctx.update(hours(1, "07:00", "21:00", ""));
        BusinessHours blank = new BusinessHours();
        blank.setStartTime("   ");
        blank.setEndTime("");
        ctx.update(blank);
        assertEquals("07:00", ctx.getHours().getStartTime());
        assertEquals("21:00", ctx.getHours().getEndTime());
    }
}
