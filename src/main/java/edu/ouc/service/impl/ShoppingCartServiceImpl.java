package edu.ouc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import edu.ouc.common.BaseContext;
import edu.ouc.entity.Dish;
import edu.ouc.entity.Setmeal;
import edu.ouc.entity.ShoppingCart;
import edu.ouc.mapper.DishMapper;
import edu.ouc.mapper.SetmealMapper;
import edu.ouc.mapper.ShoppingCartMapper;
import edu.ouc.service.IShoppingCartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Author: Sihang Xie
 * Description: 购物车接口实现类
 * Date: 2022/10/24 15:07
 * Version: 0.0.1
 * Modified By:
 *
 * 2026-10-18 隔离模型升级：
 *   购物车作用域 = 账号(userId) + 桌号(tableId)。
 *   - 不同账号 → 永不共享（避免多人点单抢同一道菜）；
 *   - 同账号 + 同桌号 → 共享同一份购物车（同桌拼单）；
 *   - 同账号 + 不同桌号 → 互相隔离。
 */
@Slf4j
@Service
public class ShoppingCartServiceImpl extends ServiceImpl<ShoppingCartMapper, ShoppingCart> implements IShoppingCartService {

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private SetmealMapper setmealMapper;

    /** 规范化桌台标识：空串统一成 null，避免 "" 与 null 被当成两个不同桌台 */
    private static String normalizeTableId(String tableId) {
        if (tableId == null) {
            return null;
        }
        String t = tableId.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 取本次请求真正生效的桌台：显式入参优先，其次取过滤器写入的线程变量。
     * 这样即使某个接口（如下单）没显式传 tableId，也能与购物车操作落在同一桌台。
     */
    private static String currentTable(String override) {
        String t = normalizeTableId(override);
        return t != null ? t : normalizeTableId(BaseContext.getCurrentTableId());
    }

    /**
     * 给查询条件追加桌台过滤（null 安全）。
     * 未传桌台时只命中 table_id 为 NULL 或空串的行，
     * 防止「外卖车」与「某桌台的车」互相串数据。
     */
    private static void appendTableCondition(LambdaQueryWrapper<ShoppingCart> lqw, String tableId) {
        String t = normalizeTableId(tableId);
        if (t != null) {
            lqw.eq(ShoppingCart::getTableId, t);
        } else {
            lqw.and(w -> w.isNull(ShoppingCart::getTableId).or().eq(ShoppingCart::getTableId, ""));
        }
    }

    // 添加菜品到购物车
    @Override
    public ShoppingCart add(ShoppingCart shoppingCart, String tableId) {
        // 获取当前登录用户的 ID
        Long userId = BaseContext.getCurrentUserId();
        String tid = currentTable(tableId);
        log.info("添加购物车: userId={}, tableId={}, dishId={}, setmealId={}",
                userId, tid, shoppingCart.getDishId(), shoppingCart.getSetmealId());
        // 安全修复：未登录绝不写入 userId 为空的脏数据（否则会变成"所有人共享"的一行）
        if (userId == null) {
            return null;
        }
        // 给传入的购物车菜品设置用户ID与桌台
        shoppingCart.setUserId(userId);
        shoppingCart.setTableId(tid);

        // 2026-10-17 修复①：菜品必须按 dishId + dishFlavor 联合判定。
        // 原实现只按 dishId 查，导致「龙井虾仁(微辣)」与「龙井虾仁(中辣)」被合并成同一行，
        // 用户在规格弹窗里选的第二个口味被直接吞掉，表现为点加菜但数量/口味不对。
        String flavor = shoppingCart.getDishFlavor();
        if (flavor != null) {
            flavor = flavor.trim();
            shoppingCart.setDishFlavor(flavor.isEmpty() ? null : flavor);
        }
        if (shoppingCart.getDishId() == null && shoppingCart.getSetmealId() == null) {
            return null;
        }

        // 查询一下是否是首次添加
        LambdaQueryWrapper<ShoppingCart> lqw = new LambdaQueryWrapper<>();
        // 添加查询条件：根据用户ID + 桌台查询（同桌同账号才算同一辆车）
        lqw.eq(ShoppingCart::getUserId, userId);
        appendTableCondition(lqw, tid);
        // 判断用户添加的是菜品还是套餐
        if (shoppingCart.getDishId() != null) {
            // 用户添加的是菜品，添加菜品ID作为查询条件
            lqw.eq(ShoppingCart::getDishId, shoppingCart.getDishId());
            if (shoppingCart.getDishFlavor() != null) {
                lqw.eq(ShoppingCart::getDishFlavor, shoppingCart.getDishFlavor());
            } else {
                // 无口味时只命中"无口味"的那一行，避免误合并到已有口味的行
                lqw.and(w -> w.isNull(ShoppingCart::getDishFlavor).or().eq(ShoppingCart::getDishFlavor, ""));
            }
        } else {
            // 否则，用户添加的是套餐，添加套餐ID作为查询条件
            lqw.eq(ShoppingCart::getSetmealId, shoppingCart.getSetmealId());
        }
        // 调用数据层查询购物车（第二个参数 false：历史脏数据导致多行时不要抛异常）
        ShoppingCart shoppingCartSel = this.getOne(lqw, false);

        // 2026-10-17 修复②：名称/图片/金额必须落库。
        // 原实现直接信任前端传参，前端没传时 amount=null、name=null、image=null，
        // 而前端 goodsPrice() 是累加 amount 的 → 底部金额恒为 ￥0.00、购物车弹层菜品名空白，
        // 用户看到的现象就是"加了菜但没反应、按钮还是灰的"。
        fillSnapshot(shoppingCart);

        // 如果查询结果为空，则是第一次添加，把number字段设为1，插入
        if (shoppingCartSel == null) {
            shoppingCart.setNumber(1);
            this.save(shoppingCart);
            return shoppingCart;
        }

        // 否则就不是第一次加入购物车，就直接在number字段上加1，更新
        int cur = shoppingCartSel.getNumber() == null ? 0 : shoppingCartSel.getNumber();
        shoppingCartSel.setNumber(cur + 1);
        // 历史脏行（金额为 0 / 名称为空）顺手补齐，否则这条菜永远是 0 元
        if (isBlank(shoppingCartSel.getName()) || isBlank(shoppingCartSel.getImage())
                || shoppingCartSel.getAmount() == null
                || shoppingCartSel.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            ShoppingCart patch = new ShoppingCart();
            patch.setDishId(shoppingCartSel.getDishId());
            patch.setSetmealId(shoppingCartSel.getSetmealId());
            patch.setName(shoppingCartSel.getName());
            patch.setImage(shoppingCartSel.getImage());
            fillSnapshot(patch);
            shoppingCartSel.setName(patch.getName());
            shoppingCartSel.setImage(patch.getImage());
            shoppingCartSel.setAmount(patch.getAmount());
        }
        // 历史行可能没有桌台，本次带桌台访问时顺手归属过去
        if (tid != null && normalizeTableId(shoppingCartSel.getTableId()) == null) {
            shoppingCartSel.setTableId(tid);
        }
        this.updateById(shoppingCartSel);
        return shoppingCartSel;
    }

    /**
     * 补齐购物车行的名称、图片、单价：前端没传就从菜品/套餐表回查。
     * 这是"底部金额恒为 ￥0.00、购物车弹层菜品名空白"的根因修复。
     */
    private void fillSnapshot(ShoppingCart cart) {
        try {
            if (cart.getDishId() != null) {
                Dish dish = dishMapper.selectById(cart.getDishId());
                if (dish != null) {
                    if (isBlank(cart.getName())) {
                        cart.setName(dish.getName());
                    }
                    if (isBlank(cart.getImage())) {
                        cart.setImage(dish.getImage());
                    }
                    if (cart.getAmount() == null && dish.getPrice() != null) {
                        cart.setAmount(dish.getPrice());
                    }
                }
            } else if (cart.getSetmealId() != null) {
                Setmeal setmeal = setmealMapper.selectById(cart.getSetmealId());
                if (setmeal != null) {
                    if (isBlank(cart.getName())) {
                        cart.setName(setmeal.getName());
                    }
                    if (isBlank(cart.getImage())) {
                        cart.setImage(setmeal.getImage());
                    }
                    if (cart.getAmount() == null && setmeal.getPrice() != null) {
                        cart.setAmount(setmeal.getPrice());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("补齐购物车快照失败(忽略): {}", e.getMessage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // 查询当前用户 + 当前桌台的购物车中所有信息
    @Override
    public List<ShoppingCart> getUserList(String tableId) {
        // 获取当前用户ID
        Long userId = BaseContext.getCurrentUserId();
        // 安全修复：未登录（userId 为 null）时必须返回空列表。
        // 原实现用 lqw.eq(userId != null, ...)，当 userId 为 null 时该条件被跳过，
        // 会查出全表所有用户的购物车数据，造成越权泄露；同时首页游客访问也会返回 NOTLOGIN。
        if (userId == null) {
            return new ArrayList<>();
        }
        String tid = currentTable(tableId);
        // 创建查询条件封装器
        LambdaQueryWrapper<ShoppingCart> lqw = new LambdaQueryWrapper<>();
        lqw.eq(ShoppingCart::getUserId, userId);
        appendTableCondition(lqw, tid);
        // 按时间升序排
        lqw.orderByAsc(ShoppingCart::getCreateTime);
        // 查询当前用户 + 当前桌台的所有购物车信息
        return this.list(lqw);
    }

    // 清空购物车
    @Override
    public Boolean clean(String tableId) {
        // 获取当前用户ID
        Long userId = BaseContext.getCurrentUserId();
        String tid = currentTable(tableId);
        log.info("清空购物车: userId={}, tableId={}", userId, tid);
        // 安全修复：未登录（userId 为 null）时必须直接返回，绝不能执行无条件删除。
        // 原实现 lqw.eq(userId != null, ...) 在 userId 为 null 时条件被跳过，
        // 会把全表所有用户的购物车清空（严重越权）。
        if (userId == null) {
            return true;
        }
        LambdaQueryWrapper<ShoppingCart> lqw = new LambdaQueryWrapper<>();
        lqw.eq(ShoppingCart::getUserId, userId);
        appendTableCondition(lqw, tid);
        // 按用户ID + 桌台删除记录
        return this.remove(lqw);
    }

    // 购物车商品减一
    @Override
    public Boolean sub(ShoppingCart shoppingCart, String tableId) {
        // 1.获取当前用户ID
        Long userId = BaseContext.getCurrentUserId();
        String tid = currentTable(tableId);
        log.info("购物车减一: userId={}, tableId={}, dishId={}, setmealId={}",
                userId, tid, shoppingCart.getDishId(), shoppingCart.getSetmealId());
        // 安全修复：未登录时不允许任何写操作，避免条件失效后误删他人数据
        if (userId == null) {
            return false;
        }
        Long dishId = shoppingCart.getDishId();
        Long setmealId = shoppingCart.getSetmealId();
        // 兼容前端只传购物车行 id 的情况
        Long cartId = shoppingCart.getId();
        if (cartId != null && dishId == null && setmealId == null) {
            ShoppingCart byId = this.getById(cartId);
            // 越权保护：只能减自己账号、自己桌号的行
            if (byId == null || !userId.equals(byId.getUserId())
                    || !java.util.Objects.equals(tid, normalizeTableId(byId.getTableId()))) {
                return false;
            }
            dishId = byId.getDishId();
            setmealId = byId.getSetmealId();
            shoppingCart.setDishFlavor(byId.getDishFlavor());
        }
        if (dishId == null && setmealId == null) {
            return false;
        }

        // 2.创建查询条件封装器
        LambdaQueryWrapper<ShoppingCart> lqw = new LambdaQueryWrapper<>();
        lqw.eq(ShoppingCart::getUserId, userId);
        appendTableCondition(lqw, tid);

        // 3.判断传来的是菜品还是套餐（菜品按 dishId + 口味区分，避免多规格互相覆盖）
        if (dishId != null) {
            lqw.eq(ShoppingCart::getDishId, dishId);
            // 2026-10-17 修复：与 add() 保持同一套口味判定。
            // 原实现在未传口味时不加口味条件，会命中同菜品「任意口味」的第一行：
            // 车里同时有「龙井虾仁(微辣)」和「龙井虾仁(中辣)」时，点减号减掉的是别人那一行，
            // 表现为"点了减号数量不动 / 减错菜"。现在未传口味时只命中"无口味"的行。
            String flavor = shoppingCart.getDishFlavor();
            if (flavor != null && !flavor.trim().isEmpty()) {
                lqw.eq(ShoppingCart::getDishFlavor, flavor.trim());
            } else {
                lqw.and(w -> w.isNull(ShoppingCart::getDishFlavor).or().eq(ShoppingCart::getDishFlavor, ""));
            }
        } else {
            lqw.eq(ShoppingCart::getSetmealId, setmealId);
        }

        // 4.查询当前购物车里该菜品/套餐
        ShoppingCart shoppingCartSel = this.getOne(lqw, false);
        // 2026-10-18 兜底修复（"点减号毫无反应、无法删除"重大BUG的根因之一）：
        // 上面的严格条件在"未传口味"时只命中无口味行，而 2026-10-18 改版后 add() 总会
        // 写入自动默认口味（如"微辣,不要葱"）。前端两个减号入口（菜品列表 / 购物车弹层）
        // 若漏带 dishFlavor，严格条件必然落空 → 返回"删除失败" → 数字永远不动、行删不掉。
        // 这里在不传口味且严格查询落空时，退化为"该菜品任意口味的最早一行"，
        // 保证任何调用方至少都能减；精准命中仍由前端带上口味完成（前端本次已修）。
        if (shoppingCartSel == null && dishId != null
                && (shoppingCart.getDishFlavor() == null
                    || shoppingCart.getDishFlavor().trim().isEmpty())) {
            LambdaQueryWrapper<ShoppingCart> anyFlavor = new LambdaQueryWrapper<>();
            anyFlavor.eq(ShoppingCart::getUserId, userId);
            appendTableCondition(anyFlavor, tid);
            anyFlavor.eq(ShoppingCart::getDishId, dishId);
            anyFlavor.orderByAsc(ShoppingCart::getId);
            shoppingCartSel = this.getOne(anyFlavor, false);
        }
        // 空值保护：原实现直接 shoppingCartSel.getNumber() 会 NPE（减少已不存在的菜时页面报错）
        if (shoppingCartSel == null) {
            return false;
        }
        Integer number = shoppingCartSel.getNumber() == null ? 0 : shoppingCartSel.getNumber();
        if (number > 1) {
            // 4.1 数量大于一，直接减一
            shoppingCartSel.setNumber(number - 1);
            // 4.2更新到数据库
            return this.updateById(shoppingCartSel);
        }

        // 5.其他情况直接从购物车删除此商品
        return this.removeById(shoppingCartSel.getId());
    }
}
