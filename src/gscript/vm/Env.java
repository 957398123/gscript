package gscript.vm;

import gscript.vm.value.GSObject;
import gscript.vm.value.GSValue;

import java.util.HashMap;

public class Env {
    /**
     * function,block,loop
     */
    public String name;

    public HashMap<String, GSValue> values = new HashMap<>();

    public Env(String name){
        this.name = name;
    }
}
