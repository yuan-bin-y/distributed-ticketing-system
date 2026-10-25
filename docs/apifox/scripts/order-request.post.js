// 用于异步受理和受理查询；CREATED以后再查询正式订单，不能把202当成出票成功。
const response=pm.response.json();
if(pm.response.code>=200&&pm.response.code<300&&response.code==='OK'&&response.data){
  const accepted=response.data;
  pm.environment.set('request_id',accepted.requestId);
  pm.environment.set('request_status',accepted.status);
  if(accepted.orderNo)pm.environment.set('order_no',accepted.orderNo);
}
