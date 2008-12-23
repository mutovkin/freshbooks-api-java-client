package com.freshbooks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import com.freshbooks.model.Categories;
import com.freshbooks.model.Category;
import com.freshbooks.model.Client;
import com.freshbooks.model.Clients;
import com.freshbooks.model.Expense;
import com.freshbooks.model.Expenses;
import com.freshbooks.model.Invoice;
import com.freshbooks.model.Invoices;
import com.freshbooks.model.PagedResponseContent;
import com.freshbooks.model.Payment;
import com.freshbooks.model.Payments;
import com.freshbooks.model.Request;
import com.freshbooks.model.RequestMethod;
import com.freshbooks.model.Response;
import com.thoughtworks.xstream.XStream;

public class ApiConnection {
    static final Logger logger = LoggerFactory.getLogger(ApiConnection.class);

    URL url;
    String key;
    String userAgent;
    transient HttpClient client;
    boolean debug;
    
    protected ApiConnection() {
        
    }
    public ApiConnection(URL apiUrl, String key, String userAgent) {
        this.url = apiUrl;
        this.key = key;
        this.userAgent = userAgent;
    }

    private HttpClient getClient() {
        if(client == null) {
            client = new HttpClient();
            client.getParams().setAuthenticationPreemptive(true);
            client.getState().setCredentials(new AuthScope(url.getHost(), 443, AuthScope.ANY_REALM), new UsernamePasswordCredentials(key, ""));
        }
        return client;
    }

    /**
     * Send a request to the FreshBooks API and return the response object.
     * @param url
     * @param postObject
     * @return
     * @throws IOException 
     * @throws Error
     */
    protected Response performRequest(Request request) throws ApiException, IOException {
        try {
            XStream xs = new CustomXStream();
            
            String paramString = xs.toXML(request);
            PostMethod method = new PostMethod(url.toString());
            try {
                method.setContentChunked(false);
                method.setDoAuthentication(true);
                method.setFollowRedirects(false);
                method.addRequestHeader("User-Agent", userAgent);
                //method.addRequestHeader("Authorization", base64key);
                method.setRequestEntity(new StringRequestEntity(paramString, "text/xml", "utf-8"));
                getClient().executeMethod(method);
                if(debug) {
                    logger.debug("POST "+url+" "+paramString+" yields: "+method.getResponseBodyAsString());
                }
                InputStream is = method.getResponseBodyAsStream();
                try {
                    Response response = (Response)xs.fromXML(new BufferedReader(new InputStreamReader(is, "utf8")));
                    // TODO Throw an error if we got one
                    if(response.isFail()) {
                        throw new ApiException(response.getError());
                    }
                    return response;
                } finally {
                    if(is != null) is.close();
                }
            } finally {
                method.releaseConnection();
            }
        } catch (MalformedURLException e) {
            throw new Error(e);
        }
    }
    
    /**
     * Create an invoice using the given information and return its id.
     * @throws ApiException If an error is returned from FreshBooks
     * @throws IOException If there is a communications error with the FreshBooks API server
     */
    public String createInvoice(Invoice invoice) throws ApiException, IOException {
        return performRequest(new Request(RequestMethod.INVOICE_CREATE, invoice)).getInvoiceId();
    }

    public URL getUrl() {
        return url;
    }

    public void setUrl(URL apiUrl) {
        this.url = apiUrl;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
    
    /**
     * Iterate over the invoices matching the given filters, or all invoices.
     * 
     * Note that the Freshbooks API only returns summaries of the invoice, not the full details
     * of the invoice.
     */
    public Iterable<Invoice> listInvoices(final Integer perPage, final Date dateFrom, final Date dateTo, final String clientId, final String status) {
        return new Iterable<Invoice>() {
            @Override
            public Iterator<Invoice> iterator() {
                try {
                    return new InvoicesIterator(perPage, dateFrom, dateTo, clientId, status);
                } catch (ApiException e) {
                    throw new Error(e);
                } catch (IOException e) {
                    throw new Error(e);
                }
            }
        };
    }
    
    /**
     * Iterate over the payments matching the given filters, or all invoices.
     */
    public Iterable<Payment> listPayments(final Integer perPage, final Date dateFrom, final Date dateTo, final String clientId) {
        return new Iterable<Payment>() {
            @Override
            public Iterator<Payment> iterator() {
                try {
                    return new PaymentsIterator(perPage, dateFrom, dateTo, clientId);
                } catch (ApiException e) {
                    throw new Error(e);
                } catch (IOException e) {
                    throw new Error(e);
                }
            }
        };
    }

    /**
     * Iterate over the expenses matching the given filters, or all invoices.
     */
    public Iterable<Expense> listExpenses(final Integer perPage, final Date dateFrom, final Date dateTo, final String clientId, final String categoryId, final String projectId) {
        return new Iterable<Expense>() {
            @Override
            public Iterator<Expense> iterator() {
                try {
                    return new ExpensesIterator(perPage, dateFrom, dateTo, clientId, categoryId, projectId);
                } catch (ApiException e) {
                    throw new Error(e);
                } catch (IOException e) {
                    throw new Error(e);
                }
            }
        };
    }
    
    /**
     * Iterate over the payments matching the given filters, or all invoices.
     */
    public Iterable<Client> listClients(final Integer perPage, final String username, final String email) {
        return new Iterable<Client>() {
            @Override
            public Iterator<Client> iterator() {
                try {
                    return new ClientsIterator(perPage, username, email);
                } catch (ApiException e) {
                    throw new Error(e);
                } catch (IOException e) {
                    throw new Error(e);
                }
            }
        };
    }
    
    abstract class RecordsIterator<T> implements Iterator<T> {
        PagedResponseContent<T> current;
        Iterator<T> currentIterator;
        final protected Integer perPage;
        final protected Date dateFrom;
        final protected Date dateTo;
        final protected String clientId;
        final protected String status;
        final protected String username;
        final protected String email;
        final protected String categoryId;
        final protected String projectId;
        
        public RecordsIterator(Integer perPage, Date dateFrom, Date dateTo, String clientId, String status, String username, String email, String categoryId, String projectId) throws ApiException, IOException {
            this.perPage = perPage;
            this.dateFrom = dateFrom;
            this.dateTo = dateTo;
            this.clientId = clientId;
            this.categoryId = categoryId;
            this.projectId = projectId;
            this.status = status;
            this.username = username;
            this.email = email;
            this.current = list(1);
            this.currentIterator = current.iterator();
        }

        protected abstract PagedResponseContent<T> list(int page) throws ApiException, IOException;

        @Override
        public boolean hasNext() {
            return currentIterator.hasNext() || current.getPage() < current.getPages();
        }
        
        @Override
        public T next() {
            if(!currentIterator.hasNext()) {
                if(current.getPage() >= current.getPages())
                    throw new NoSuchElementException();
                try {
                    current = list(current.getPage()+1);
                } catch (ApiException e) {
                    throw new NoSuchElementException(e.getLocalizedMessage());
                } catch (IOException e) {
                    throw new NoSuchElementException(e.getLocalizedMessage());
                }
                currentIterator = current.iterator();
            }
            return currentIterator.next();
        }
        
        @Override
        public void remove() {
            throw new NotImplementedException();
        }
    }
    
    class InvoicesIterator extends RecordsIterator<Invoice> {

        private InvoicesIterator(Integer perPage, Date dateFrom, Date dateTo, String clientId, String status) throws ApiException, IOException {
            super(perPage, dateFrom, dateTo, clientId, status, null, null, null, null);
        }

        @Override
        protected PagedResponseContent<Invoice> list(int page) throws ApiException, IOException {
            return listInvoices(page, perPage, dateFrom, dateTo, clientId, status);
        }
    }
    
    class PaymentsIterator extends RecordsIterator<Payment> {

        private PaymentsIterator(Integer perPage, Date dateFrom, Date dateTo, String clientId) throws ApiException, IOException {
            super(perPage, dateFrom, dateTo, clientId, null, null, null, null, null);
        }
        
        @Override
        protected PagedResponseContent<Payment> list(int page) throws ApiException, IOException {
            return listPayments(page, perPage, dateFrom, dateTo, clientId);
        }
    }
    class ExpensesIterator extends RecordsIterator<Expense> {

        private ExpensesIterator(Integer perPage, Date dateFrom, Date dateTo, String clientId, String categoryId, String projectId) throws ApiException, IOException {
            super(perPage, dateFrom, dateTo, clientId, null, null, null, categoryId, projectId);
        }
        
        @Override
        protected PagedResponseContent<Expense> list(int page) throws ApiException, IOException {
            return listExpenses(page, perPage, dateFrom, dateTo, clientId, categoryId, projectId);
        }
    }
    class ClientsIterator extends RecordsIterator<Client> {

        private ClientsIterator(Integer perPage, String username, String email) throws ApiException, IOException {
            super(perPage, null, null, null, null, username, email, null, null);
        }
        
        @Override
        protected PagedResponseContent<Client> list(int page) throws ApiException, IOException {
            return listClients(page, perPage, username, email);
        }
    }
    
    /**
     * Return a list of invoices.
     * 
     * @param dateFrom If non-null, return only payments after that day
     * @param dateTo If non-null, return only payments before that day
     * @param clientId If non-null, return only payments relevant to a particular client
     */
    public Invoices listInvoices(int page, Integer perPage, Date dateFrom, Date dateTo, String clientId, String status) throws ApiException, IOException {
        Request request = new Request(RequestMethod.INVOICE_LIST);
        request.setPage(page);
        request.setPerPage(perPage);
        request.setDateFrom(dateFrom);
        request.setDateTo(dateTo);
        request.setClientId(clientId);
        request.setStatus(status);
        return performRequest(request).getInvoices();
    }
    /**
     * Get a list of payments.
     * 
     * @param dateFrom If non-null, return only payments after that day
     * @param dateTo If non-null, return only payments before that day
     * @param clientId If non-null, return only payments relevant to a particular client
     */
    public Payments listPayments(int page, Integer perPage, Date dateFrom, Date dateTo, String clientId) throws ApiException, IOException {
        Request request = new Request(RequestMethod.PAYMENT_LIST);
        request.setPage(page);
        request.setPerPage(perPage);
        request.setDateFrom(dateFrom);
        request.setDateTo(dateTo);
        request.setClientId(clientId);
        return performRequest(request).getPayments();
    }
    
    /**
     * Get a list of expenses.
     * 
     * @param dateFrom If non-null, return only expenses after that day
     * @param dateTo If non-null, return only expenses before that day
     * @param clientId If non-null, return only expenses relevant to a particular client
     */
    public Expenses listExpenses(int page, Integer perPage, Date dateFrom, Date dateTo, String clientId, String categoryId, String projectId) throws ApiException, IOException {
        Request request = new Request(RequestMethod.EXPENSE_LIST);
        request.setPage(page);
        request.setPerPage(perPage);
        request.setDateFrom(dateFrom);
        request.setDateTo(dateTo);
        request.setClientId(clientId);
        request.setProjectId(projectId);
        request.setCategoryId(categoryId);
        return performRequest(request).getExpenses();
    }
    
    
    /**
     * Get a list of clients.  The items returned are client summaries which do
     * not include the full address information.
     * 
     * @param username If non-null, include only clients with a matching username
     * @param email If non-null, include only clients with a matching email address
     * @return
     * @throws ApiException
     * @throws IOException
     */
    public Clients listClients(int page, Integer perPage, String username, String email) throws ApiException, IOException {
        Request request = new Request(RequestMethod.CLIENT_LIST);
        request.setPage(page);
        request.setPerPage(perPage);
        request.setUsername(username);
        request.setEmail(email);
        return performRequest(request).getClients();
    }

    /**
     * Get all the categories defined
     */
    public Categories listCategories() throws ApiException, IOException {
        return performRequest(new Request(RequestMethod.CATEGORY_LIST)).getCategories();
    }
    
    /**
     * Fetch the details of a client.
     */
    public Client getClient(String id) throws ApiException, IOException {
        return performRequest(new Request(RequestMethod.CLIENT_GET, id)).getClient();
    }
    
    /**
     * Fetch the details of an invoice
     */
    public Invoice getInvoice(String id) throws ApiException, IOException {
        return performRequest(new Request(RequestMethod.INVOICE_GET, id)).getInvoice();
    }
    
    /**
     * Fetch the details of an expense
     */
    public Expense getExpense(String id) throws ApiException, IOException { 
        return performRequest(new Request(RequestMethod.EXPENSE_GET, id)).getExpense();
    }
    
    /**
     * Get category details by id
     */
    public Category getCategory(String id) throws ApiException, IOException {
        return performRequest(new Request(RequestMethod.CATEGORY_GET, id)).getCategory();
    }
    
    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }
}
