package com.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.review.entity.Voucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;


public interface VoucherMapper extends BaseMapper<Voucher> {

    @Select("SELECT * FROM tb_voucher WHERE shop_id = #{shopId}")
    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);

}
