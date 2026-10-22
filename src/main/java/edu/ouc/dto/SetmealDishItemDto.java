package edu.ouc.dto;

import edu.ouc.entity.SetmealDish;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 套餐内菜品明细（C端套餐详情弹窗使用）
 * 在 setmeal_dish 冗余字段基础上，补充 dish 表的 image / description，
 * 以便前端直接展示菜品图片与描述。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SetmealDishItemDto extends SetmealDish {

    private static final long serialVersionUID = 1L;

    // 菜品图片文件名（来自 dish.image）
    private String image;
    // 菜品描述（来自 dish.description）
    private String description;
}
