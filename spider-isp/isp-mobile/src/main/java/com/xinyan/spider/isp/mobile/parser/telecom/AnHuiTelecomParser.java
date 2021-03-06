package com.xinyan.spider.isp.mobile.parser.telecom;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.xinyan.spider.isp.base.CacheContainer;
import com.xinyan.spider.isp.base.Context;
import com.xinyan.spider.isp.base.ProcessorCode;
import com.xinyan.spider.isp.base.Result;
import com.xinyan.spider.isp.base.StatusCode;
import com.xinyan.spider.isp.common.utils.CollectionUtil;
import com.xinyan.spider.isp.common.utils.DateUtils;
import com.xinyan.spider.isp.common.utils.PageUtils;
import com.xinyan.spider.isp.common.utils.RegexUtils;
import com.xinyan.spider.isp.common.utils.StringUtils;
import com.xinyan.spider.isp.mobile.model.CarrierBillDetailInfo;
import com.xinyan.spider.isp.mobile.model.CarrierCallDetailInfo;
import com.xinyan.spider.isp.mobile.model.CarrierInfo;
import com.xinyan.spider.isp.mobile.model.CarrierNetDetailInfo;
import com.xinyan.spider.isp.mobile.model.CarrierPackageItemInfo;
import com.xinyan.spider.isp.mobile.model.CarrierSmsRecordInfo;
import com.xinyan.spider.isp.mobile.model.CarrierUserRechargeItemInfo;

import jxl.Sheet;
import jxl.Workbook;
import jxl.read.biff.BiffException;

/**
 * 安徽电信数据解析类
 * @description
 * @author yyj
 * @date 2017年5月9日 下午4:33:47
 * @version V1.0
 */
@Component
public class AnHuiTelecomParser {

    protected static Logger logger = LoggerFactory.getLogger(AnHuiTelecomParser.class);

	public Result parse(Context context, CarrierInfo ci, CacheContainer cc){
		Result result = new Result();
		try{
			logger.info("==>[{}]正在解析运营商用户信息...", context.getTaskId());
			//基本信息
			Page page = cc.getPage(ProcessorCode.BASIC_INFO.getCode());
			String pageInfo = page.getWebResponse().getContentAsString();
			pageInfo = pageInfo.replaceAll("\\s", " ").replaceAll("\"", "'");
			
			ci.getCarrierUserInfo().setName(RegexUtils.matchValue("姓</span>名：</label>(.*?)</li> ", pageInfo).trim());//姓名
			ci.getCarrierUserInfo().setIdCard(context.getIdCard());//证件号
			ci.getCarrierUserInfo().setCarrier("CHINA_TELECOM");//运营商    CHINA_MOBILE 中国移动    CHINA_TELECOM 中国电信    CHINA_UNICOM 中国联通
			ci.getCarrierUserInfo().setProvince("安徽");//所属省份
			ci.getCarrierUserInfo().setCity(context.getMobileHCodeDto().getCityName());//所属城市
			ci.getCarrierUserInfo().setLevel("");//帐号星级
			ci.getCarrierUserInfo().setOpenTime(RegexUtils.matchValue("入网时间：</label>(.*?)</li>  ", pageInfo).trim());//入网时间，格式：yyyy-MM-dd
    	    ci.getCarrierUserInfo().setState(0);//帐号状态, -1未知 0正常 1单向停机 2停机 3预销户 4销户 5过户 6改号 99号码不存在
			//余额
			page = cc.getPage(ProcessorCode.AMOUNT.getCode());
			if(null != page){
				Double amount = Double.parseDouble(RegexUtils.matchValue("\"can_use_balance\":\"(.*?)\"", page.getWebResponse().getContentAsString())) * 100;
				ci.getCarrierUserInfo().setAvailableBalance(amount.intValue());//当前可用余额（单位: 分）
			}
			
			//地址
			page = cc.getPage(ProcessorCode.OTHER_INFO.getCode());
			if(null != page){
				pageInfo = page.getWebResponse().getContentAsString();
				pageInfo = pageInfo.replaceAll("\\s", " ").replaceAll("\"", "'");
				String[] tmps = RegexUtils.matchMutiValue("<input type='hidden' name='deliveryAddress.userCode' id='userCode' value='.*' ></input>\\s*<td>(.*?)</td>\\s*<td>(.*?)</td>\\s*<td style='.*'>(.*?)</td>\\s*<td></td>", pageInfo);
				if(null != tmps && tmps.length > 2){
					ci.getCarrierUserInfo().setAddress(tmps[1] + " " + tmps[2]);//地址
				}
			}
			
			//套餐名称
			page = cc.getPage(ProcessorCode.POINTS_VALUE.getCode());
			if(null != page){
				JSONObject tmp = JSONObject.parseObject(page.getWebResponse().getContentAsString());
				if(null != tmp){
					JSONArray tmps = tmp.getJSONArray("mainMealList");
					if(CollectionUtil.isNotEmpty(tmps) && tmps.size() > 0){
						ci.getCarrierUserInfo().setPackageName(tmps.getJSONObject(0).getString("name"));//套餐名称
					}
				}
			}
			ci.getCarrierUserInfo().setLastModifyTime(DateUtils.dateToString(new Date(), "yyyy-MM-dd hh:mm:ss"));//最近一次更新时间，格式: yyyy-MM-dd HH:mm:ss

			logger.info("==>[{}]正在解析通话详单...", context.getTaskId());
	        List<Page> pages = cc.getPages(ProcessorCode.CALLRECORD_INFO.getCode());
	        CarrierCallDetailInfo ccdi = new CarrierCallDetailInfo();
	        for (Page p : pages) {
	        	if(null != p){
	    			try {
	    				InputStream input = p.getWebResponse().getContentAsStream();
	    				Workbook book = Workbook.getWorkbook(input);
	    				Sheet sheet = book.getSheet(0);
	    				for(int i=2; i<sheet.getRows()-1; i++){
    						ccdi = new CarrierCallDetailInfo();
    						ccdi.setMappingId(ci.getMappingId());//映射id
    						String time = sheet.getCell(6,i).getContents();
    						ccdi.setTime(time);//通话时间，格式：yyyy-MM-dd HH:mm:ss
    						ccdi.setPeerNumber(sheet.getCell(4,i).getContents());//对方号码
    						ccdi.setLocation(sheet.getCell(5,i).getContents());//通话地(自己的)
    						ccdi.setLocationType(sheet.getCell(2,i).getContents());//通话地类型. e.g.省内漫游
    						String tmp = sheet.getCell(7,i).getContents();
    						if(StringUtils.isNotEmpty(tmp)){
    							String[] tmps = tmp.split(":");
    							if(null!= tmps && tmps.length > 2){
    								int dur = 0;
    								dur += Integer.parseInt(tmps[0]) * 3600;
    								dur += Integer.parseInt(tmps[1]) * 60;
    								dur += Integer.parseInt(tmps[2]);
    								ccdi.setDuration(dur+"");//通话时长(单位:秒)
    							}
    						}
    						tmp = sheet.getCell(1,i).getContents();
    						if(tmp.contains("主叫")){
    							ccdi.setDialType("DIAL");//DIAL-主叫; DIALED-被叫
    						}else if(tmp.contains("被叫")){
    							ccdi.setDialType("DIALED");//DIAL-主叫; DIALED-被叫
    						}else{
    							ccdi.setDialType(tmp);//DIAL-主叫; DIALED-被叫
    						}
    						tmp = sheet.getCell(8,i).getContents();
    						Double totalFee = Double.parseDouble(tmp) * 100;
    						ccdi.setFee(totalFee.intValue());//通话费(单位:分)
    						ccdi.setBillMonth(time.substring(0, time.lastIndexOf("-")));//通话月份
    						ci.getCalls().add(ccdi);
    					}
	    				book.close();
	    				input.close();
	    			} catch (IOException e) {
	    				logger.error("==>[{}]解析通话详单出错了:", context.getTaskId(), e);
	    			} catch (BiffException e) {
	    				logger.error("==>[{}]无法解析或该月无通话详情:", context.getTaskId(), e);
	    			}
				}
	        }

			logger.info("==>[{}]正在解析短信记录...", context.getTaskId());
	        pages = cc.getPages(ProcessorCode.SMS_INFO.getCode());
	        CarrierSmsRecordInfo csri = new CarrierSmsRecordInfo();
	        for (Page p : pages) {
	        	if(null != p){
	    			try {
	    				InputStream input = p.getWebResponse().getContentAsStream();
	    				Workbook book = Workbook.getWorkbook(input);
	    				Sheet sheet = book.getSheet(0);
	    				for(int i=2; i<sheet.getRows()-1; i++){
    						csri = new CarrierSmsRecordInfo();
	    					csri.setMappingId(ci.getMappingId());//映射id
	    					String time = sheet.getCell(4,i).getContents();
							csri.setTime(time);//收/发短信时间，格式：yyyy-MM-dd HH:mm:ss
							csri.setBillMonth(time.substring(0, time.lastIndexOf("-")));//通话月份
	    					csri.setPeerNumber(sheet.getCell(3,i).getContents());//对方号码
	    					csri.setLocation("");//通话地(自己的)
	    					String tmp = sheet.getCell(1,i).getContents();
	    					if(tmp.contains("短信")){
	    						csri.setMsgType("SMS");//SMS-短信; MMS-彩信
	    					}else if(tmp.contains("彩信")){
	    						csri.setMsgType("MMS");//SMS-短信; MMS-彩信
	    					}else{
	    						csri.setMsgType(tmp);//SMS-短信; MMS-彩信
	    					}
	    					csri.setServiceName(tmp);//业务名称. e.g. 点对点(网内)
	    					
	    					tmp = sheet.getCell(2,i).getContents();
	    					if(tmp.contains("发")){
	    						csri.setSendType("SEND");//SEND-发送; RECEIVE-收取
	    					}else if(tmp.contains("收")){
	    						csri.setSendType("RECEIVE");//SEND-发送; RECEIVE-收取
	    					}else{
	    						csri.setSendType(tmp);//SEND-发送; RECEIVE-收取
	    					}
	    					
	    					tmp = sheet.getCell(5,i).getContents();
    						Double totalFee = Double.parseDouble(tmp) * 100;
    						csri.setFee(totalFee.intValue());//通话费(单位:分)
	        				ci.getSmses().add(csri);
    					}
	    				book.close();
	    				input.close();
	    			} catch (IOException e) {
	    				logger.error("==>[{}]解析短信出错了:", context.getTaskId(), e);
	    			} catch (BiffException e) {
	    				logger.error("==>[{}]无法解析或该月无短信记录:", context.getTaskId(), e);
	    			}
				}
	        }

			logger.info("==>[{}]正在解析上网记录...", context.getTaskId());
	        pages = cc.getPages(ProcessorCode.NET_INFO.getCode());
	        CarrierNetDetailInfo cndi = new CarrierNetDetailInfo();
	        for (Page p : pages) {
	        	if(null != p){
	    			try {
	    				InputStream input = p.getWebResponse().getContentAsStream();
	    				Workbook book = Workbook.getWorkbook(input);
	    				Sheet sheet = book.getSheet(0);
	    				for(int i=2; i<sheet.getRows(); i++){
	    					String tmp = sheet.getCell(0,i).getContents();
	    					if(!StringUtils.contains(tmp, "-")){
	    						cndi = new CarrierNetDetailInfo();
	    						cndi.setMappingId(ci.getMappingId());//映射id
	    						
	    						String time = sheet.getCell(1,i).getContents();
	    						cndi.setTime(time);//上网时间，格式：yyyy-MM-dd HH:mm:ss
	    						cndi.setBillMonth(time.substring(0, time.lastIndexOf("-")));
	    						
	    						int dur = 0;
	    						tmp = sheet.getCell(2,i).getContents();
		    					String durs = RegexUtils.matchValue("(\\d+)小?时", tmp);
		    					if(StringUtils.isNotEmpty(durs)){
		    						dur += Integer.parseInt(durs) * 3600;
		    					}
		    					durs = RegexUtils.matchValue("(\\d+)分", tmp);
		    					if(StringUtils.isNotEmpty(durs)){
		    						dur += Integer.parseInt(durs) * 60;
		    					}
		    					durs = RegexUtils.matchValue("(\\d+)秒", tmp);
		    					if(StringUtils.isNotEmpty(durs)){
		    						dur += Integer.parseInt(durs);
		    					}
	    						cndi.setDuration(dur);//流量使用时长
	    						
	    						tmp = sheet.getCell(3,i).getContents();
		    					durs = RegexUtils.matchValue("(\\d+)GB", tmp);
		    					
		    					dur = 0;
		    					if(StringUtils.isNotEmpty(durs)){
		    						dur += Integer.parseInt(durs) * 1048576;
		    					}
		    					durs = RegexUtils.matchValue("(\\d+)MB", tmp);
		    					if(StringUtils.isNotEmpty(durs)){
		    						dur += Integer.parseInt(durs) * 1024;
		    					}
		    					durs = RegexUtils.matchValue("(\\d+)KB", tmp);
		    					if(StringUtils.isNotEmpty(durs)){
		    						dur += Integer.parseInt(durs);
		    					}
	    						cndi.setSubflow(dur);//流量使用量，单位:KB
	    						
	    						cndi.setLocation(sheet.getCell(5,i).getContents());//流量使用地点
	    						cndi.setNetType(sheet.getCell(4,i).getContents());//网络类型
	    						cndi.setServiceName("");//业务名称
	    						
	    						tmp = sheet.getCell(6,i).getContents();
	    						Double totalFee = Double.parseDouble(tmp) * 100;
	    						cndi.setFee(totalFee.intValue());//通信费(单位:分)
	    						ci.getNets().add(cndi);
	    					}
    					}
	    				book.close();
	    				input.close();
	    			} catch (IOException e) {
	    				logger.error("==>[{}]解析上网出错了:", context.getTaskId(), e);
	    			} catch (BiffException e) {
	    				logger.error("==>[{}]无法解析或该月无上网记录:", context.getTaskId(), e);
	    			}
				}
	        }

	        logger.info("==>[{}]正在解析账单记录...", context.getTaskId());
	        pages = cc.getPages(ProcessorCode.BILL_INFO.getCode());
	        CarrierBillDetailInfo cbdi = new CarrierBillDetailInfo();
	        Double fee;
	        for (Page p : pages) {
	        	if(null != p){
	        		pageInfo = p.getWebResponse().getContentAsString();
    				pageInfo = pageInfo.replaceAll("&nbsp;", "");
    				pageInfo = pageInfo.replaceAll("\\s", " ").replaceAll("\"", "'");
    				

    				cbdi = new CarrierBillDetailInfo();
	        	    cbdi.setMappingId(ci.getMappingId());//映射id
	        	    String[] tmps = RegexUtils.matchMutiValue("账单周期：</th>\\s*<td>(.*?) 至(.*?)</td>", pageInfo);
	        	    if(null != tmps && tmps.length > 1){
		        	    cbdi.setBillMonth(tmps[0].substring(0,7));//账单月，格式：yyyy-MM
		        	    cbdi.setBillStartDate(tmps[0]);//账期起始日期，格式：yyyy-MM-dd
		        	    cbdi.setBillEndDate(tmps[1]);//账期结束日期，格式：yyyy-MM-dd
		        	    
		        	    List<HtmlElement> elements = PageUtils.getElementByXpath(p, "//div[@id='s2']/table/tbody/tr/td/dl/dd");
		        	    for(HtmlElement e : elements){
		        	    	tmps = e.asText().split(" ");
		        	    	if(null != tmps && tmps.length > 1){
		        	    		fee = Double.parseDouble(tmps[0]) * 100;
		        	    		if(tmps[1].contains("通话费")){
		        	    			cbdi.setVoiceFee(cbdi.getVoiceFee() + fee.intValue());//语音费 单位分
		        	    		}else if(tmps[1].contains("上网费")){
		        	    			cbdi.setWebFee(cbdi.getWebFee() + fee.intValue());//网络流量费 单位分
		        	    		}else if(tmps[1].contains("短信费")){
		        	    			cbdi.setSmsFee(cbdi.getSmsFee() + fee.intValue());//短彩信费 单位分
		        	    		}else if(tmps[1].contains("来电显示费")){
		        	    			cbdi.setExtraFee(cbdi.getExtraFee() + fee.intValue());//语音费 单位分
		        	    		}
		        	    	}
		        	    }
		        	    
		        	    String feeStr = RegexUtils.matchValue("<label class='s1 fr'> </label> 语音通信费.* <label class='s1 fr'>(.*?)</label> 本地通话费  </dd>", pageInfo);
		        	    if(StringUtils.isNotEmpty(feeStr)){
		        	    	fee = Double.parseDouble(feeStr) * 100;
		        	    	cbdi.setBaseFee(fee.intValue());//套餐及固定费 单位分
		        	    }
		        	    feeStr = RegexUtils.matchValue("本地通话费.*</label> 综合信息服务费.*<label class='s1 fr'>(.*?)</label> 来电显示费  </dd>", pageInfo);
		        	    if(StringUtils.isNotEmpty(feeStr)){
		        	    	fee = Double.parseDouble(feeStr) * 100;
		        	    	cbdi.setExtraServiceFee(fee.intValue());//增值业务费 单位分
		        	    }
		        	    feeStr = RegexUtils.matchValue("本期费用合计.*<em class='fb red'>(.*?)</em></li> ", pageInfo);
		        	    if(StringUtils.isNotEmpty(feeStr)){
		        	    	fee = Double.parseDouble(feeStr) * 100;
		        	    	cbdi.setTotalFee(fee.intValue());//总费用 单位分
		        	    }
		        	    tmps = RegexUtils.matchMutiValue("<td align='right'>null</td>\\s*<td width='20' align='center'>=</td>\\s*<td align='center'>(.*?)</td>\\s*<td width='20' align='center'>-</td>\\s*<td align='center'>(.*?)</td>\\s*<td width='20' align='center'>", pageInfo);
		        	    if(null != tmps && tmps.length > 1){
		        	    	if("null".equals(tmps[0]) && StringUtils.isNotEmpty(tmps[0])){
		        	    		cbdi.setPoint(0);//本期可用积分
		        	    	}else{
		        	    		cbdi.setPoint(Integer.parseInt(tmps[0]));//本期可用积分
		        	    	}
		        	    	if("null".equals(tmps[0]) && StringUtils.isNotEmpty(tmps[1])){
		        	    		cbdi.setLastPoint(0);//上期可用积分
		        	    	}else{
		        	    		cbdi.setLastPoint(Integer.parseInt(tmps[1]));//上期可用积分
		        	    	}
		        	    }
		        	    cbdi.setRelatedMobiles("");//本手机关联号码, 多个手机号以逗号分隔
		        	    cbdi.setNotes(RegexUtils.matchValue("<strong style='margin-left: 25px;'>(.*?)</strong>", pageInfo));//备注
		        	    ci.getBills().add(cbdi);
	        	    }
        		}
	        }

	        logger.info("==>[{}]正在解析套餐信息...", context.getTaskId());
	        Page p = cc.getPage(ProcessorCode.PACKAGE_ITEM.getCode());
        	if(null != p){
        		pageInfo = p.getWebResponse().getContentAsString();
        		JSONObject tmp = JSONObject.parseObject(p.getWebResponse().getContentAsString());
        		JSONArray tmps = tmp.getJSONArray("data");
        		CarrierPackageItemInfo cpti = new CarrierPackageItemInfo();
        		Date d = new Date();
				if(CollectionUtil.isNotEmpty(tmps)){
					for(int i=0; i<tmps.size(); i++){
						JSONObject j = (JSONObject) tmps.get(i);
						cpti = new CarrierPackageItemInfo();
						cpti.setMappingId(ci.getMappingId());//映射id
						cpti.setItem(j.getString("itemName"));//套餐项目名称
						
						String durs = j.getString("itemName");
    					if(StringUtils.contains(durs, "流量")){
    						cpti.setUnit("KB");//单位：语音-分; 流量-KB; 短/彩信-条
    						
    						durs = j.getString("total");
        					if(StringUtils.isNotEmpty(durs)){
        						fee = Double.parseDouble(durs) * 1024;
        						cpti.setTotal(fee.intValue()+"");//项目总量
        					}
        					durs = j.getString("used");
        					if(StringUtils.isNotEmpty(durs)){
        						fee = Double.parseDouble(durs) * 1024;
        						cpti.setUsed(fee.intValue()+"");//项目已使用量
        					}
    					}else{
    						cpti.setUnit("分");//单位：语音-分; 流量-KB; 短/彩信-条
    						
    						durs = j.getString("total");
        					if(StringUtils.isNotEmpty(durs)){
        						fee = Double.parseDouble(durs);
        						cpti.setTotal(fee.intValue()+"");//项目总量
        					}
        					durs = j.getString("used");
        					if(StringUtils.isNotEmpty(durs)){
        						fee = Double.parseDouble(durs);
        						cpti.setUsed(fee.intValue()+"");//项目已使用量
        					}
    					}
						
    					durs = j.getString("expireTime");
   						cpti.setBillStartDate(DateUtils.getFirstDay(d, "yyyy-MM-dd"));//账单起始日, 格式为yyyy-MM-dd
   						if(StringUtils.isNotEmpty(durs) && durs.length() > 10){
   							cpti.setBillEndDate(durs.substring(0, 10));//账单结束日, 格式为yyyy-MM-dd
   						}else{
   							cpti.setBillEndDate(DateUtils.getCurrentDate());//账单结束日, 格式为yyyy-MM-dd
   						}
						ci.getPackages().add(cpti);
					}
				}
        	}

	        logger.info("==>[{}]正在解析充值记录...", context.getTaskId());
	        page = cc.getPage(ProcessorCode.RECHARGE_INFO.getCode());
	        if(null != page){
	        	CarrierUserRechargeItemInfo curi = new CarrierUserRechargeItemInfo();
	        	List<HtmlElement> trs = PageUtils.getElementByXpath(page, "//table[@class='tabsty']/tbody/tr");
	        	for(HtmlElement tr : trs){
	        		curi = new CarrierUserRechargeItemInfo();
	        		List<HtmlElement> tds = PageUtils.getElementByXpath(tr, "td");
	        		String tmp = tds.get(0).asText().trim();
	        		if(!StringUtils.contains("总计", tmp)){
	        			curi.setMappingId(ci.getMappingId());//映射id
	        			String startDate = tds.get(1).asText().trim();
	        			curi.setBillMonth(startDate.substring(0, startDate.lastIndexOf("-")));//账单月，格式：yyyy-MM
	        			curi.setRechargeTime(startDate);//充值时间，格式：yyyy-MM-dd HH:mm:ss
	        			tmp = tds.get(2).asText().trim();
	        			if(StringUtils.isNotEmpty(tmp)){
	        				if(StringUtils.contains(tmp, "-")){
	        					tmp = tds.get(3).asText().trim();
	        				}
	        				try{
	        					fee = Double.parseDouble(tmp) * 100;
	        					curi.setAmount(fee.intValue());//充值金额(单位: 分)
	        				}catch(Exception e){logger.info("==>[{}]金额解析错误", context.getTaskId());}
	        			}
	        			curi.setType(tds.get(0).asText().trim());//充值方式. e.g. 现金
	        			ci.getRecharges().add(curi);
	        		}
	        	}
			}
	        result.setData(ci);
	        result.setResult(StatusCode.解析成功);
		}catch(Exception e){
			logger.info("==>[{}]解析出错了:", context.getTaskId(), e);
			result.setResult(StatusCode.数据解析中发生错误);
		}
	    return result;
	}
}