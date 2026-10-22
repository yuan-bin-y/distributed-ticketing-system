param(
    [ValidateRange(1,100000)][int]$MySqlMaxConnections=150,
    [ValidateRange(0,100000)][int]$ReservedConnections=30,
    [ValidateRange(1,1000)][int]$EventInstances=1,
    [ValidateRange(1,10000)][int]$EventConcurrency=16,
    [ValidateRange(1,100000)][int]$EventLoadBudget=16,
    [ValidateRange(1,10000)][int]$EventPool=20,
    [ValidateRange(0,10000)][int]$EventReserve=4,
    [ValidateRange(1,1000)][int]$OrderInstances=2,
    [ValidateRange(1,1000)][int]$AuthInstances=1,
    [ValidateRange(1,1000)][int]$InventoryInstances=1,
    [ValidateRange(1,1000)][int]$PaymentInstances=1,
    [ValidateRange(0,1000)][int]$NacosInstances=0,
    [ValidateRange(1,10000)][int]$ServicePool=20,
    [ValidateRange(1,10000)][int]$NacosPool=10
)
$ErrorActionPreference='Stop'
$loads=[long]$EventInstances*$EventConcurrency
$connections=[long]$EventInstances*$EventPool+[long]($OrderInstances+$AuthInstances+$InventoryInstances+$PaymentInstances)*$ServicePool+[long]$NacosInstances*$NacosPool
if($loads -gt $EventLoadBudget){throw 'Event总回源并发超过预算；扩容前降低单实例额度或重新压测预算。'}
if($EventConcurrency+$EventReserve -gt $EventPool){throw 'Event连接池没有为发布、恢复和指标查询留下预留。'}
if($connections+$ReservedConnections -gt $MySqlMaxConnections){throw '业务连接池总上限加运维预留超过MySQL连接数。'}
[pscustomobject]@{EventPlannedLoads=$loads;EventLoadBudget=$EventLoadBudget;PoolConnections=$connections;ReservedConnections=$ReservedConnections;MySqlMaxConnections=$MySqlMaxConnections} | Format-List
