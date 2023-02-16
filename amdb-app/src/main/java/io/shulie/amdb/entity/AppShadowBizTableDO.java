package io.shulie.amdb.entity;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.Date;

@Data
@ApiModel
@Table(name = "`t_amdb_app_shadowbiztable`")
public class AppShadowBizTableDO extends BaseDatabaseDO {
    private static final long serialVersionUID = -3663076393735881721L;

    @Id
    @Column(name = "`id`")
    @ApiModelProperty("应用ID")
    private Long id;

    @ApiModelProperty("应用名称")
    @Column(name = "`app_name`")
    private String appName;

    @ApiModelProperty("业务库")
    @Column(name = "`biz_database`")
    private String bizDatabase;

    @ApiModelProperty("业务表")
    @Column(name = "`table_name`")
    private String tableName;

    @ApiModelProperty("用户名")
    @Column(name = "`table_user`")
    private String tableUser;

    @ApiModelProperty("有写入操作")
    @Column(name = "`can_write`")
    private Integer canWrite;

    @ApiModelProperty("有读取操作")
    @Column(name = "`can_read`")
    private Integer canRead;

    @ApiModelProperty("unique_key")
    @Column(name = "`unique_key`")
    private String uniqueKey;

    @Column(name = "`gmt_create`")
    @ApiModelProperty("创建时间")
    private Date gmtCreate;

    @Column(name = "`gmt_modify`")
    @ApiModelProperty("更新时间")
    private Date gmtModify;
}
