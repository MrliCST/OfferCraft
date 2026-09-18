package com.example.dao.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.dao.dao.ChatMessageDO;

/**
 * MyBatis-Plus 的 Mapper，只管 chat_message 单表 CRUD。
 * 基础增删改查由 BaseMapper 提供，只有在需要手写 SQL 时才在这里加 @Select / @Update。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {
}
