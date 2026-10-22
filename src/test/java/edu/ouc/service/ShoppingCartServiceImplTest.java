package edu.ouc.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.ouc.entity.Dish;
import edu.ouc.common.BaseContext;
import edu.ouc.entity.ShoppingCart;
import edu.ouc.mapper.DishMapper;
import edu.ouc.mapper.SetmealMapper;
import edu.ouc.mapper.ShoppingCartMapper;
import edu.ouc.service.impl.ShoppingCartServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 购物车服务层单元测试（纯 Mockito，不启动 Spring 容器、不连数据库）
 *
 * 重点覆盖 2026-10-17/18 两次修复的核心不变量：
 *   1. 购物车作用域 = 账号(userId) + 桌号(tableId)，未登录绝不写库 / 绝不返回他人数据；
 *   2. 菜品按 dishId + dishFlavor 联合判定（加菜 & 减菜规则必须一致）；
 *   3. 名称/图片/金额快照必须落库，避免前端金额为 0 导致"去结算"按钮灰掉。
 *
 * @since 2026-10-17
 */
class ShoppingCartServiceImplTest {

    @Mock
    private ShoppingCartMapper shoppingCartMapper;
    @Mock
    private DishMapper dishMapper;
    @Mock
    private SetmealMapper setmealMapper;

    private ShoppingCartServiceImpl service;

    private AutoCloseable mocks;

    /**
     * 纯单测环境没有 MyBatis 配置，LambdaQueryWrapper 只有在实体 TableInfo 初始化后
     * 才能把 ShoppingCart::getTableId 解析成列名（否则 getSqlSegment() 抛
     * "can not find lambda cache for this entity"）。这里手动初始化一次。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ShoppingCart.class);
    }

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        // 注意：不能用 @InjectMocks —— ServiceImpl 的 baseMapper 字段声明为泛型 M，
        // 运行期擦除成 BaseMapper，而 DishMapper/SetmealMapper/ShoppingCartMapper 三个
        // 候选都继承 BaseMapper，Mockito 会注入错对象导致后续调用 NPE。
        // 这里用反射按字段名精确注入。
        service = new ShoppingCartServiceImpl();
        // mock 默认返回 0，会让 updateById/deleteById 表现为「失败」，这里显式打桩为成功
        when(shoppingCartMapper.updateById(any())).thenReturn(1);
        when(shoppingCartMapper.deleteById(any(java.io.Serializable.class))).thenReturn(1);
        when(shoppingCartMapper.deleteById(any(ShoppingCart.class))).thenReturn(1);
        when(shoppingCartMapper.delete(any())).thenReturn(1);
        when(shoppingCartMapper.insert(any())).thenReturn(1);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baseMapper", shoppingCartMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "setmealMapper", setmealMapper);
        BaseContext.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        BaseContext.clear();
        mocks.close();
    }

    // ------------------------------------------------------------------ 工具

    private static Dish dish(Long id, String name, BigDecimal price) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setPrice(price);
        d.setImage("dish_" + id + ".jpg");
        return d;
    }

    private static ShoppingCart cart(Long dishId, String flavor) {
        ShoppingCart c = new ShoppingCart();
        c.setDishId(dishId);
        c.setDishFlavor(flavor);
        return c;
    }

    /**
     * 取出被 mock 掉的 mapper 收到的最后一个查询条件。
     * 注意：ServiceImpl.getOne(wrapper, throwEx) 走的是 baseMapper.selectOne(wrapper, throwEx)，
     * 而 list() 走的是 baseMapper.selectList(wrapper)，两者要分开捕获。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbstractWrapper<ShoppingCart, String, ?> capturedQuery() {
        ArgumentCaptor<AbstractWrapper> cap = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(shoppingCartMapper, atLeastOnce()).selectOne(cap.capture(), anyBoolean());
        return cap.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbstractWrapper<ShoppingCart, String, ?> capturedListQuery() {
        ArgumentCaptor<AbstractWrapper> cap = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(shoppingCartMapper, atLeastOnce()).selectList(cap.capture());
        return cap.getValue();
    }

    /** 给「按条件查车」打桩：getOne() 底层调用的是 selectOne(wrapper, throwEx) */
    private void stubExistingRow(ShoppingCart row) {
        when(shoppingCartMapper.selectOne(any(), anyBoolean())).thenReturn(row);
    }

    /** 给「查不到」打桩 */
    private void stubNoRow() {
        when(shoppingCartMapper.selectOne(any(), anyBoolean())).thenReturn(null);
    }

    /**
     * 2026-10-18 兜底路径打桩：第一次查询（严格条件：无口味行）落空，
     * 第二次查询（任意口味行）命中给定行。用于复现"选了口味的菜点减号删不掉"。
     */
    private void stubStrictMissThenAnyFlavorRow(ShoppingCart row) {
        when(shoppingCartMapper.selectOne(any(), anyBoolean()))
                .thenReturn(null)
                .thenReturn(row);
    }

    // ------------------------------------------------------------------ 加菜

    @Test
    @DisplayName("加菜：未登录时返回 null 且绝不写库（防止产生 userId 为空的共享脏数据）")
    void add_whenNotLogin_returnsNullAndNeverInsert() {
        BaseContext.setCurrentUserId(null);
        ShoppingCart result = service.add(cart(100L, null), "9");
        assertNull(result);
        verify(shoppingCartMapper, never()).insert(any());
    }

    @Test
    @DisplayName("加菜：dishId 与 setmealId 均为空时不写库")
    void add_whenNoDishAndNoSetmeal_returnsNull() {
        BaseContext.setCurrentUserId(1001L);
        ShoppingCart result = service.add(new ShoppingCart(), "9");
        assertNull(result);
        verify(shoppingCartMapper, never()).insert(any());
    }

    @Test
    @DisplayName("加菜：首次加入 number=1，并回填名称/图片/金额快照")
    void add_firstTime_fillsSnapshotAndNumberOne() {
        BaseContext.setCurrentUserId(1001L);
        when(dishMapper.selectById(100L)).thenReturn(dish(100L, "糖醋排骨", new BigDecimal("58.00")));
        stubNoRow();

        ShoppingCart result = service.add(cart(100L, null), "9");

        assertNotNull(result);
        assertEquals(1, result.getNumber());
        assertEquals("糖醋排骨", result.getName());
        assertEquals("dish_100.jpg", result.getImage());
        assertEquals(new BigDecimal("58.00"), result.getAmount());
        assertEquals(1001L, result.getUserId());
        assertEquals("9", result.getTableId());
        verify(shoppingCartMapper).insert(any(ShoppingCart.class));
    }

    @Test
    @DisplayName("加菜：同一菜品同一口味重复加入时数量累加，而不是新增一行")
    void add_sameDishSameFlavor_incrementsNumber() {
        BaseContext.setCurrentUserId(1001L);
        when(dishMapper.selectById(100L)).thenReturn(dish(100L, "糖醋排骨", new BigDecimal("58.00")));

        ShoppingCart exist = new ShoppingCart();
        exist.setId(1L);
        exist.setDishId(100L);
        exist.setDishFlavor("微辣");
        exist.setNumber(2);
        exist.setName("糖醋排骨");
        exist.setImage("dish_100.jpg");
        exist.setAmount(new BigDecimal("58.00"));
        stubExistingRow(exist);

        ShoppingCart result = service.add(cart(100L, "微辣"), "9");

        assertEquals(3, result.getNumber(), "同口味应累加到 3");
        verify(shoppingCartMapper).updateById(any(ShoppingCart.class));
        verify(shoppingCartMapper, never()).insert(any());
    }

    @Test
    @DisplayName("加菜：同一菜品不同口味必须各占一行（龙井虾仁微辣 / 中辣不被合并）")
    void add_sameDishDifferentFlavor_keepsSeparateRows() {
        BaseContext.setCurrentUserId(1001L);
        when(dishMapper.selectById(100L)).thenReturn(dish(100L, "龙井虾仁", new BigDecimal("88.00")));
        // 车里已有一行「微辣」
        ShoppingCart exist = new ShoppingCart();
        exist.setId(1L);
        exist.setDishId(100L);
        exist.setDishFlavor("微辣");
        exist.setNumber(1);
        when(shoppingCartMapper.selectList(any())).thenReturn(new ArrayList<>(Collections.singletonList(exist)));

        // 现在加「中辣」：查询条件带 flavor=中辣，mock 返回空 → 应插入新行
        ShoppingCart result = service.add(cart(100L, "中辣"), "9");

        assertNotNull(result);
        assertEquals(1, result.getNumber());
        assertEquals("中辣", result.getDishFlavor());
        verify(shoppingCartMapper).insert(any(ShoppingCart.class));
        verify(shoppingCartMapper, never()).updateById(any());
    }

    // ------------------------------------------------------------------ 查询

    @Test
    @DisplayName("查询：未登录返回空列表，且不查询数据库（防止越权看到全表）")
    void list_whenNotLogin_returnsEmpty() {
        BaseContext.setCurrentUserId(null);
        List<ShoppingCart> result = service.getUserList("9");
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(shoppingCartMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("查询：条件里必须同时带 userId 与 tableId（账号+桌号双维度隔离）")
    void list_queryContainsUserIdAndTableId() {
        BaseContext.setCurrentUserId(1001L);
        when(shoppingCartMapper.selectList(any())).thenReturn(new ArrayList<>());

        service.getUserList("9");

        AbstractWrapper<ShoppingCart, String, ?> q = capturedListQuery();
        String seg = q.getSqlSegment();
        assertTrue(seg.contains("user_id"), "查询条件缺少 user_id: " + seg);
        assertTrue(seg.contains("table_id"), "查询条件缺少 table_id: " + seg);
        assertTrue(q.getParamNameValuePairs().values().contains("9"),
                "tableId 参数未绑定，实际: " + q.getParamNameValuePairs());
    }

    @Test
    @DisplayName("查询：未传桌号时只命中无桌台的行（外卖车不串桌台车）")
    void list_withoutTableId_matchesOnlyNullTable() {
        BaseContext.setCurrentUserId(1001L);
        when(shoppingCartMapper.selectList(any())).thenReturn(new ArrayList<>());

        service.getUserList(null);

        AbstractWrapper<ShoppingCart, String, ?> q = capturedListQuery();
        String seg = q.getSqlSegment();
        assertTrue(seg.contains("IS NULL") || seg.toUpperCase().contains("IS NULL"),
                "无桌台查询应包含 IS NULL 条件: " + seg);
    }

    // ------------------------------------------------------------------ 减菜

    @Test
    @DisplayName("减菜：未登录不允许任何写操作")
    void sub_whenNotLogin_returnsFalseAndNeverDelete() {
        BaseContext.setCurrentUserId(null);
        boolean ok = service.sub(cart(100L, "微辣"), "9");
        assertFalse(ok);
        verify(shoppingCartMapper, never()).deleteById(any());
        verify(shoppingCartMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("减菜：数量 > 1 时只减一，不删行")
    void sub_numberGreaterThanOne_decrement() {
        BaseContext.setCurrentUserId(1001L);
        ShoppingCart exist = new ShoppingCart();
        exist.setId(1L);
        exist.setDishId(100L);
        exist.setDishFlavor("微辣");
        exist.setNumber(3);
        stubExistingRow(exist);

        boolean ok = service.sub(cart(100L, "微辣"), "9");

        assertTrue(ok);
        assertEquals(2, exist.getNumber());
        verify(shoppingCartMapper).updateById(exist);
        verify(shoppingCartMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("减菜：数量 = 1 时直接删除整行")
    void sub_numberEqualsOne_deleteRow() {
        BaseContext.setCurrentUserId(1001L);
        ShoppingCart exist = new ShoppingCart();
        exist.setId(1L);
        exist.setDishId(100L);
        exist.setDishFlavor("微辣");
        exist.setNumber(1);
        stubExistingRow(exist);

        assertTrue(service.sub(cart(100L, "微辣"), "9"));
        verify(shoppingCartMapper).deleteById(1L);
    }

    @Test
    @DisplayName("减菜：未传口味时只命中「无口味」的行（与 add() 规则一致，防止减错行）")
    void sub_withoutFlavor_matchesOnlyNullFlavorRow() {
        BaseContext.setCurrentUserId(1001L);
        ShoppingCart noFlavor = new ShoppingCart();
        noFlavor.setId(7L);
        noFlavor.setDishId(100L);
        noFlavor.setDishFlavor(null);
        noFlavor.setNumber(1);
        stubExistingRow(noFlavor);

        assertTrue(service.sub(cart(100L, null), "9"));

        AbstractWrapper<ShoppingCart, String, ?> q = capturedQuery();
        String seg = q.getSqlSegment();
        assertTrue(seg.contains("dish_flavor"), "减菜条件应带口味判定: " + seg);
        // 关键：必须是 (dish_flavor IS NULL OR dish_flavor = '') 而不是完全不限制口味
        assertTrue(seg.toUpperCase().contains("IS NULL"),
                "未传口味时必须只命中无口味行（IS NULL），实际: " + seg);
        verify(shoppingCartMapper).deleteById(7L);
    }

    @Test
    @DisplayName("减菜：车里没有这道菜时返回 false，不抛异常（防止 NPE）")
    void sub_notFound_returnsFalse() {
        BaseContext.setCurrentUserId(1001L);
        stubNoRow();
        assertFalse(service.sub(cart(999L, null), "9"));
    }

    @Test
    @DisplayName("减菜：未传口味且没有无口味行时，兜底减该菜品任意口味最早的行（防止选了口味的菜永远减不动）")
    void sub_withoutFlavor_fallbackToAnyFlavorRow_decrement() {
        BaseContext.setCurrentUserId(1001L);
        // 复现线上重大BUG：add() 写入了默认口味"微辣,不要葱"，
        // 前端减号历史上漏带 dishFlavor → 严格条件（无口味行）必然查不到。
        ShoppingCart flavored = new ShoppingCart();
        flavored.setId(42L);
        flavored.setDishId(100L);
        flavored.setDishFlavor("微辣,不要葱");
        flavored.setNumber(2);
        stubStrictMissThenAnyFlavorRow(flavored);

        assertTrue(service.sub(cart(100L, null), "9"));

        assertEquals(1, flavored.getNumber());
        verify(shoppingCartMapper).updateById(flavored);
        verify(shoppingCartMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("减菜：兜底命中后数量为 1 时同样删行（选了口味的菜也删得掉）")
    void sub_fallback_numberEqualsOne_deleteRow() {
        BaseContext.setCurrentUserId(1001L);
        ShoppingCart flavored = new ShoppingCart();
        flavored.setId(42L);
        flavored.setDishId(100L);
        flavored.setDishFlavor("微辣,不要葱");
        flavored.setNumber(1);
        stubStrictMissThenAnyFlavorRow(flavored);

        assertTrue(service.sub(cart(100L, ""), "9")); // 空字符串口味同样触发兜底

        verify(shoppingCartMapper).deleteById(42L);
    }

    @Test
    @DisplayName("减菜：按购物车行 id 操作时，不允许减掉他人账号/他桌的行")
    void sub_byCartIdCrossAccount_rejected() {
        BaseContext.setCurrentUserId(1001L);
        ShoppingCart other = new ShoppingCart();
        other.setId(5L);
        other.setUserId(2002L);          // 别人的账号
        other.setTableId("8");           // 别人的桌号
        other.setDishId(100L);
        other.setNumber(1);
        other.setDishFlavor("微辣");
        when(shoppingCartMapper.selectById(5L)).thenReturn(other);

        ShoppingCart probe = new ShoppingCart();
        probe.setId(5L);
        assertFalse(service.sub(probe, "9"));
        verify(shoppingCartMapper, never()).deleteById(any());
        verify(shoppingCartMapper, never()).updateById(any());
    }

    // ------------------------------------------------------------------ 清空

    @Test
    @DisplayName("清空：未登录直接返回，绝不执行无条件删除（防止清空全表）")
    void clean_whenNotLogin_neverDelete() {
        BaseContext.setCurrentUserId(null);
        assertTrue(service.clean("9"));
        verify(shoppingCartMapper, never()).delete(any());
    }

    @Test
    @DisplayName("清空：条件必须绑定 userId 与 tableId")
    void clean_conditionHasUserIdAndTableId() {
        BaseContext.setCurrentUserId(1001L);
        when(shoppingCartMapper.delete(any())).thenReturn(1);

        assertTrue(service.clean("9"));

        ArgumentCaptor<AbstractWrapper> cap = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(shoppingCartMapper).delete(cap.capture());
        String seg = cap.getValue().getSqlSegment();
        assertTrue(seg.contains("user_id"), seg);
        assertTrue(seg.contains("table_id"), seg);
    }
}
