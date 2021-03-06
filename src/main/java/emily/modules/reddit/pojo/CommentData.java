/*
 * Copyright 2017 github.com/kaaz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package emily.modules.reddit.pojo;


import com.google.gson.annotations.Expose;

/**
 * Created by Siddharth Verma on 5/5/16.
 */
public class CommentData {

    @Expose
    public String author;

    @Expose
    public String body;

    @Expose
    public Long created;

    @Expose
    public Long created_utc;

    @Expose
    public String subreddit;

    @Expose
    public Integer score;

    @Expose
    public String id;

    @Expose
    public InitialDataComment replies;
    public boolean isOp;
}
