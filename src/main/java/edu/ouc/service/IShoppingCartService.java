package edu.ouc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import edu.ouc.entity.ShoppingCart;

import java.util.List;

/**
 * Author: Sihang Xie
 * Description: 购物车业务层接口
 * Date: 2022/10/24 15:06
 * Version: 0.0.1
 * Modified By:
 *
 * 2026-10-18：购物车作用域从「仅当前用户」改为「当前用户 + 桌台」。
 * 所有方法新增 tableId 参数，用于区分同一账号下的不同桌号；
 * 只有 userId 与 tableId 同时相同才共享同一份购物车。
 */
public interface IShoppingCartService extends IService<ShoppingCart> {

    // 添加菜品到购物车（tableId 为空表示不区分桌台，如外卖）
    ShoppingCart add(ShoppingCart shoppingCart, String tableId);

    // 查询当前用户 + 当前桌台的购物车中所有信息
    List<ShoppingCart> getUserList(String tableId);

    // 清空当前用户 + 当前桌台的购物车
    Boolean clean(String tableId);

    // 购物车商品减一
    Boolean sub(ShoppingCart shoppingCart, String tableId);
}
