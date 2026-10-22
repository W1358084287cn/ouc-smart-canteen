-- ============================================
-- OUC 智慧食堂 — 数据库初始化脚本
-- 使用方法：登录 MySQL 后执行 source init.sql
-- ============================================

CREATE DATABASE IF NOT EXISTS reggie
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE reggie;

-- 1. 员工表
CREATE TABLE IF NOT EXISTS employee (
    id BIGINT NOT NULL COMMENT '主键',
    name VARCHAR(32) NOT NULL COMMENT '姓名',
    username VARCHAR(32) NOT NULL COMMENT '用户名',
    password VARCHAR(64) NOT NULL COMMENT '密码',
    phone VARCHAR(11) NOT NULL COMMENT '手机号',
    sex VARCHAR(2) NOT NULL COMMENT '性别',
    id_number VARCHAR(18) NOT NULL COMMENT '身份证号',
    status INT DEFAULT 1 COMMENT '状态 0禁用 1启用',
    create_time DATETIME COMMENT '创建时间',
    update_time DATETIME COMMENT '更新时间',
    create_user BIGINT COMMENT '创建人',
    update_user BIGINT COMMENT '修改人',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工表';

-- 2. 用户表（C端顾客）
CREATE TABLE IF NOT EXISTS user (
    id BIGINT NOT NULL COMMENT '主键',
    name VARCHAR(50) COMMENT '用户名',
    email VARCHAR(100) COMMENT '邮箱',
    sex VARCHAR(2) COMMENT '性别',
    id_number VARCHAR(18) COMMENT '身份证号',
    avatar VARCHAR(500) COMMENT '头像',
    status INT DEFAULT 1 COMMENT '状态 0禁用 1启用',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 3. 分类表
CREATE TABLE IF NOT EXISTS category (
    id BIGINT NOT NULL COMMENT '主键',
    type INT COMMENT '类型 1菜品分类 2套餐分类',
    name VARCHAR(64) NOT NULL COMMENT '分类名称',
    sort INT DEFAULT 0 COMMENT '排序',
    create_time DATETIME COMMENT '创建时间',
    update_time DATETIME COMMENT '更新时间',
    create_user BIGINT COMMENT '创建人',
    update_user BIGINT COMMENT '修改人',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品及套餐分类';

-- 4. 菜品表
CREATE TABLE IF NOT EXISTS dish (
    id BIGINT NOT NULL COMMENT '主键',
    name VARCHAR(64) NOT NULL COMMENT '菜品名称',
    category_id BIGINT NOT NULL COMMENT '分类ID',
    price DECIMAL(10,2) DEFAULT 0 COMMENT '价格',
    code VARCHAR(64) COMMENT '商品码',
    image VARCHAR(200) COMMENT '图片',
    description VARCHAR(400) COMMENT '描述',
    status INT DEFAULT 1 COMMENT '状态 0停售 1启售',
    sort INT DEFAULT 0 COMMENT '排序',
    is_deleted INT DEFAULT 0 COMMENT '是否删除',
    create_time DATETIME COMMENT '创建时间',
    update_time DATETIME COMMENT '更新时间',
    create_user BIGINT COMMENT '创建人',
    update_user BIGINT COMMENT '修改人',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品表';

-- 5. 菜品口味表
CREATE TABLE IF NOT EXISTS dish_flavor (
    id BIGINT NOT NULL COMMENT '主键',
    dish_id BIGINT NOT NULL COMMENT '菜品ID',
    name VARCHAR(64) COMMENT '口味名称',
    value VARCHAR(500) COMMENT '口味数据list',
    is_deleted INT DEFAULT 0 COMMENT '是否删除',
    create_time DATETIME COMMENT '创建时间',
    update_time DATETIME COMMENT '更新时间',
    create_user BIGINT COMMENT '创建人',
    update_user BIGINT COMMENT '修改人',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品口味关系表';

-- 6. 套餐表
CREATE TABLE IF NOT EXISTS setmeal (
    id BIGINT NOT NULL COMMENT '主键',
    category_id BIGINT NOT NULL COMMENT '分类ID',
    name VARCHAR(64) NOT NULL COMMENT '套餐名称',
    price DECIMAL(10,2) DEFAULT 0 COMMENT '价格',
    status INT DEFAULT 1 COMMENT '状态 0停售 1启售',
    code VARCHAR(64) COMMENT '套餐码',
    description VARCHAR(400) COMMENT '描述',
    image VARCHAR(200) COMMENT '图片',
    is_deleted INT DEFAULT 0 COMMENT '是否删除',
    create_time DATETIME COMMENT '创建时间',
    update_time DATETIME COMMENT '更新时间',
    create_user BIGINT COMMENT '创建人',
    update_user BIGINT COMMENT '修改人',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐表';

-- 7. 套餐菜品关联表
CREATE TABLE IF NOT EXISTS setmeal_dish (
    id BIGINT NOT NULL COMMENT '主键',
    setmeal_id BIGINT NOT NULL COMMENT '套餐ID',
    dish_id BIGINT NOT NULL COMMENT '菜品ID',
    name VARCHAR(64) COMMENT '菜品名称（冗余）',
    price DECIMAL(10,2) COMMENT '菜品原价',
    copies INT DEFAULT 1 COMMENT '份数',
    sort INT DEFAULT 0 COMMENT '排序',
    is_deleted INT DEFAULT 0 COMMENT '是否删除',
    create_time DATETIME COMMENT '创建时间',
    update_time DATETIME COMMENT '更新时间',
    create_user BIGINT COMMENT '创建人',
    update_user BIGINT COMMENT '修改人',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐菜品关系表';

-- 8. 购物车表
CREATE TABLE IF NOT EXISTS shopping_cart (
    id BIGINT NOT NULL COMMENT '主键',
    name VARCHAR(64) COMMENT '商品名称',
    image VARCHAR(200) COMMENT '图片',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    dish_id BIGINT COMMENT '菜品ID',
    setmeal_id BIGINT COMMENT '套餐ID',
    dish_flavor VARCHAR(100) COMMENT '口味',
    number INT DEFAULT 1 COMMENT '数量',
    amount DECIMAL(10,2) DEFAULT 0 COMMENT '金额',
    create_time DATETIME COMMENT '创建时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车表';

-- 9. 订单表
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL COMMENT '主键',
    number VARCHAR(50) COMMENT '订单号',
    status INT DEFAULT 1 COMMENT '1待付款 2制作中 3已完成 5已取消 6已退款 7退款申请中 8部分退款',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    address_book_id BIGINT DEFAULT NULL COMMENT '地址簿ID',
    order_time DATETIME COMMENT '下单时间',
    checkout_time DATETIME COMMENT '支付时间',
    pay_method INT DEFAULT 1 COMMENT '支付方式',
    amount DECIMAL(10,2) DEFAULT 0 COMMENT '实收金额',
    remark VARCHAR(200) COMMENT '备注',
    phone VARCHAR(50) COMMENT '电话',
    address VARCHAR(200) COMMENT '地址',
    user_name VARCHAR(50) COMMENT '用户名',
    consignee VARCHAR(50) COMMENT '收货人',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 10. 订单明细表
CREATE TABLE IF NOT EXISTS order_detail (
    id BIGINT NOT NULL COMMENT '主键',
    name VARCHAR(64) COMMENT '商品名称',
    image VARCHAR(200) COMMENT '图片',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    dish_id BIGINT COMMENT '菜品ID',
    setmeal_id BIGINT COMMENT '套餐ID',
    dish_flavor VARCHAR(100) COMMENT '口味',
    number INT DEFAULT 1 COMMENT '数量',
    amount DECIMAL(10,2) DEFAULT 0 COMMENT '金额',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- 插入默认管理员账号（密码 123456 的 MD5）
INSERT IGNORE INTO employee (id, name, username, password, phone, sex, id_number, status, create_time, update_time, create_user, update_user)
VALUES (1, '管理员', 'admin', 'e10adc3949ba59abbe56e057f20f883e', '13800000000', '男', '110101199001010001', 1, NOW(), NOW(), 1, 1);