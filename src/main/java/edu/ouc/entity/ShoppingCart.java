package edu.ouc.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Author: Sihang Xie
 * Description: 购物车实体类
 * Date: 2022/10/24 14:58
 * Version: 0.0.1
 * Modified By:
 */
@Data
public class ShoppingCart implements Serializable {
    private static final long serialVersionUID = 4L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String name;

    private String image;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 桌台/桌号标识。
     * 2026-10-18 新增：购物车作用域由「仅 userId」升级为「userId + tableId」。
     * 目的：同一账号下不同桌号互不干扰；只有「同账号 + 同桌号」才共享同一份购物车。
     */
    private String tableId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long dishId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long setmealId;

    private String dishFlavor;

    private Integer number;

    private BigDecimal amount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}