package edu.ouc.controller;

import edu.ouc.common.R;
import edu.ouc.entity.ShoppingCart;
import edu.ouc.service.ISharedCartService;
import edu.ouc.service.impl.ShoppingCartServiceImpl;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Author: Sihang Xie
 * Description: 购物车控制层
 * Date: 2022/10/24 15:09
 * Version: 0.0.1
 * Modified By:
 *
 * 2026-10-17 修复：移除「桌台共享购物车合并」逻辑。
 * 原实现 list() 会把 Redis 里按 tableId 存的共享购物车与当前用户购物车合并返回，
 * 而这些共享购物车记录下单后不会被清理，导致：
 *   1) 同一桌台的所有扫码用户看到同一份菜品（跨用户串数据）；
 *   2) 下单后菜品仍留在购物车，表现为"结算没反应/加菜重复"。
 *
 * 2026-10-18 隔离模型升级：
 * 购物车作用域 = 账号(userId) + 桌号(tableId)。
 *   - 不同账号：永不共享（避免多人点单抢同一道菜）；
 *   - 同账号 + 同桌号：共享同一份购物车（同桌拼单）；
 *   - 同账号 + 不同桌号：互相隔离。
 * 桌号由 tableId 请求参数传入，缺省时取过滤器写入的线程变量（BaseContext）。
 */
@Slf4j
@RestController
@RequestMapping("/shoppingCart")
@RequiredArgsConstructor
public class ShoppingCartController {

    private final ShoppingCartServiceImpl shoppingCartService;

    /** 兼容保留：其它模块若注入该服务仍可编译，但购物车流程不再使用共享车 */
    private final ISharedCartService sharedCartService;

    // 添加菜品到购物车
    @PostMapping("/add")
    public R<ShoppingCart> add(@RequestBody ShoppingCart shoppingCart,
                                @RequestParam(required = false) String tableId) {
        log.info("添加购物车: dishId={}, setmealId={}, flavor={}, tableId={}",
                shoppingCart.getDishId(), shoppingCart.getSetmealId(), shoppingCart.getDishFlavor(), tableId);
        ShoppingCart result = shoppingCartService.add(shoppingCart, tableId);
        return R.success(result);
    }

    // 查询当前用户 + 当前桌台的购物车（仅本人本桌，永不合并他车）
    @GetMapping("/list")
    public R<List<ShoppingCart>> list(@RequestParam(required = false) String tableId) {
        return R.success(shoppingCartService.getUserList(tableId));
    }

    // 清空购物车（仅清当前用户 + 当前桌台）
    @DeleteMapping("/clean")
    public R<String> clean(@RequestParam(required = false) String tableId) {
        log.info("清空购物车: tableId={}", tableId);
        if (shoppingCartService.clean(tableId)) {
            // 同时清掉该桌台的历史残留（旧版本按 tableId 存过共享车），避免旧数据复活
            if (tableId != null && !tableId.isEmpty()) {
                try {
                    sharedCartService.clearSharedCart(tableId);
                } catch (Exception e) {
                    log.warn("清理历史共享购物车失败(忽略): {}", e.getMessage());
                }
            }
            return R.success("清空成功");
        }
        return R.error("清空失败");
    }

    // 购物车商品减一
    @PostMapping("/sub")
    public R<String> sub(@RequestBody ShoppingCart shoppingCart,
                          @RequestParam(required = false) String tableId) {
        log.info("购物车减一: dishId={}, setmealId={}, tableId={}",
                shoppingCart.getDishId(), shoppingCart.getSetmealId(), tableId);
        if (shoppingCartService.sub(shoppingCart, tableId)) {
            return R.success("删除成功");
        }
        return R.error("删除失败");
    }
}
