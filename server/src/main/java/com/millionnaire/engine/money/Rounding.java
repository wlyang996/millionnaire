package com.millionnaire.engine.money;

/** 整数除法的舍入方向（数学意义上，对负数同样成立）。 */
public enum Rounding {
    /** 向负无穷取整。 */
    FLOOR,
    /** 向正无穷取整。 */
    CEIL
}
