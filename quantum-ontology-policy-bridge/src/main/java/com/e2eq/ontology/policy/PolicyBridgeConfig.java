
package com.e2eq.ontology.policy;

public class PolicyBridgeConfig {
    public boolean enabled = true;
    public boolean materialize = true;
    public String[] domains = new String[]{"Orders","Logistics"};
    public int maxChainLen = 2;
}
