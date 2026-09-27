package com.github.lisama569.apigatewayjava.api;

/**
 * 批量调用的三种整体结果状态，与单条结果严格区分，便于调用方决定
 * 「整批重试」还是「只重试失败项」：
 * <ul>
 *   <li>{@link #ALL_SUCCESS}：全部子请求成功；</li>
 *   <li>{@link #PARTIAL_SUCCESS}：有成功也有失败，成功项保留、失败项逐条可归因；</li>
 *   <li>{@link #ALL_FAILED}：全部子请求失败，不得伪装成部分成功。</li>
 * </ul>
 */
public enum BatchStatus {
    ALL_SUCCESS,
    PARTIAL_SUCCESS,
    ALL_FAILED
}
