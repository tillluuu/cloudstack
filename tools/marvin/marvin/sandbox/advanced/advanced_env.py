#!/usr/bin/env python

'''
############################################################
# Experimental state of scripts 
#    * Need to be reviewed
#    * Only a sandbox
############################################################
'''
import random
import marvin
from ConfigParser import SafeConfigParser
from optparse import OptionParser
from marvin.configGenerator import *


def getGlobalSettings(config):
   for k, v in dict(config.items('globals')).iteritems():
        cfg = configuration()
        cfg.name = k
        cfg.value = v
        yield cfg


def describeResources(config):
    zs = cloudstackConfiguration()

    z = zone()
    z.dns1 = config.get('environment', 'dns')
    z.internaldns1 = config.get('environment', 'dns')
    z.name = 'Sandbox-%s'%(config.get('cloudstack', 'hypervisor'))
    z.networktype = 'Advanced'
    z.guestcidraddress = '10.1.1.0/24'
    
    vpcprovider = provider()
    vpcprovider.name = 'VpcVirtualRouter'
    
    pn = physical_network()
    pn.name = "Sandbox-pnet"
    pn.traffictypes = [traffictype("Guest"), traffictype("Management"), traffictype("Public")]
    pn.providers.append(vpcprovider)
    
    z.physical_networks.append(pn)

    p = pod()
    p.name = 'POD0'
    p.gateway = config.get('cloudstack', 'private.gateway')
    p.startip =  config.get('cloudstack', 'private.pod.startip')
    p.endip =  config.get('cloudstack', 'private.pod.endip')
    p.netmask = config.get('cloudstack', 'private.netmask')

    v = iprange()
    v.gateway = config.get('cloudstack', 'public.gateway')
    v.startip = config.get('cloudstack', 'public.vlan.startip')
    v.endip = config.get('cloudstack', 'public.vlan.endip') 
    v.netmask = config.get('cloudstack', 'public.netmask')
    v.vlan = config.get('cloudstack', 'public.vlan')
    z.ipranges.append(v)

    c = cluster()
    c.clustername = 'C0'
    c.hypervisor = config.get('cloudstack', 'hypervisor')
    c.clustertype = 'CloudManaged'

    h = host()
    h.username = 'root'
    h.password = config.get('cloudstack', 'host.password')
    h.url = 'http://%s'%(config.get('cloudstack', 'host'))
    c.hosts.append(h)

    ps = primaryStorage()
    ps.name = 'PS0'
    ps.url = config.get('cloudstack', 'primary.pool')
    c.primaryStorages.append(ps)

    p.clusters.append(c)
    z.pods.append(p)

    secondary = secondaryStorage()
    secondary.url = config.get('cloudstack', 'secondary.pool')
    z.secondaryStorages.append(secondary)

    '''Add zone'''
    zs.zones.append(z)

    '''Add mgt server'''
    mgt = managementServer()
    mgt.mgtSvrIp = config.get('environment', 'mshost')
    zs.mgtSvr.append(mgt)

    '''Add a database'''
    db = dbServer()
    db.dbSvr = config.get('environment', 'mysql.host')
    db.user = config.get('environment', 'mysql.cloud.user')
    db.passwd = config.get('environment', 'mysql.cloud.passwd')
    zs.dbSvr = db

    '''Add some configuration'''
    [zs.globalConfig.append(cfg) for cfg in getGlobalSettings(config)]

    ''''add loggers'''
    testClientLogger = logger()
    testClientLogger.name = 'TestClient'
    testClientLogger.file = '/var/log/testclient.log'

    testCaseLogger = logger()
    testCaseLogger.name = 'TestCase'
    testCaseLogger.file = '/var/log/testcase.log'

    zs.logger.append(testClientLogger)
    zs.logger.append(testCaseLogger)
    return zs


if __name__ == '__main__':
    parser = OptionParser()
    parser.add_option('-i', '--input', action='store', default='setup.properties', \
                      dest='input', help='file containing environment setup information')
    parser.add_option('-o', '--output', action='store', default='./sandbox.cfg', \
                      dest='output', help='path where environment json will be generated')


    (opts, args) = parser.parse_args()

    cfg_parser = SafeConfigParser()
    cfg_parser.read(opts.input)

    cfg = describeResources(cfg_parser)
    generate_setup_config(cfg, opts.output)
