package org.jboss.as.jdr.resource.filter;

import org.jboss.as.jdr.resource.Resource;

/**
 * Created with IntelliJ IDEA.
 * User: csams
 * Date: 11/4/12
 * Time: 3:16 PM
 * To change this template use File | Settings | File Templates.
 */
public class NotFilter implements ResourceFilter {

    private final ResourceFilter filter;

    public NotFilter(ResourceFilter filter){
        this.filter = filter;
    }
    @Override
    public boolean accepts(Resource resource) {
        return !this.filter.accepts(resource);
    }
}
